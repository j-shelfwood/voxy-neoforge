import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Patches Voxy World Gen V2 to fix c2me deadlock and SEND_POOL termination.
 *
 * Patch 1 — ChunkGenerationManager.lambda$workerLoop$6:
 *   Changes the executor passed to whenCompleteAsync from
 *   {@code this.server} (MinecraftServer, whose thread c2me can block
 *   via managedBlock) to {@code ForkJoinPool.commonPool()}.
 *
 * Patch 2 — NetworkHandler:
 *   Removes ACC_FINAL from SEND_POOL and changes shutdown() to
 *   re-create the pool instead of leaving it permanently terminated.
 *   Fixes RejectedExecutionException on the second world load in
 *   single-player (integrated server restart).
 */
public class PatchVWGV2 {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java PatchVWGV2 <input.jar> <output.jar>");
            System.exit(1);
        }

        var inJar = Paths.get(args[0]);
        var outJar = Paths.get(args[1]);

        var cgmClass = "com/ethan/voxyworldgenv2/core/ChunkGenerationManager.class";
        var nhClass = "com/ethan/voxyworldgenv2/network/NetworkHandler.class";

        try (var zos = new ZipOutputStream(Files.newOutputStream(outJar));
             var zis = new ZipInputStream(Files.newInputStream(inJar))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                var name = entry.getName();
                var bytes = zis.readAllBytes();

                if (name.equals(cgmClass)) {
                    System.out.println("Patching CGM: " + name);
                    bytes = patchCGM(bytes);
                } else if (name.equals(nhClass)) {
                    System.out.println("Patching NH:  " + name);
                    bytes = patchNetworkHandler(bytes);
                }

                var outEntry = new ZipEntry(name);
                outEntry.setTime(entry.getTime());
                zos.putNextEntry(outEntry);
                zos.write(bytes);
                zos.closeEntry();
            }
        }

        System.out.println("Done. Output: " + outJar);
    }

    // ── ChunkGenerationManager ────────────────────────────────────────

    private static byte[] patchCGM(byte[] classBytes) {
        var cr = new ClassReader(classBytes);
        var cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                              String sig, String[] exs) {
                var mv = super.visitMethod(access, name, desc, sig, exs);
                if (name.equals("lambda$workerLoop$6")) {
                    return new ExecutorPatcher(mv);
                }
                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }

    /**
     * Replaces {@code aload_0; getfield server} with
     * {@code invokestatic ForkJoinPool.commonPool()}.
     */
    private static class ExecutorPatcher extends MethodVisitor {
        private boolean pendingAload0;

        ExecutorPatcher(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        private void flushPending() {
            if (pendingAload0) {
                pendingAload0 = false;
                super.visitVarInsn(Opcodes.ALOAD, 0);
            }
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            if (opcode == Opcodes.ALOAD && varIndex == 0) {
                flushPending();
                pendingAload0 = true;
            } else {
                flushPending();
                super.visitVarInsn(opcode, varIndex);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (pendingAload0
                && opcode == Opcodes.GETFIELD
                && name.equals("server")
                && owner.equals("com/ethan/voxyworldgenv2/core/ChunkGenerationManager")) {
                pendingAload0 = false;
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/util/concurrent/ForkJoinPool",
                    "commonPool",
                    "()Ljava/util/concurrent/ForkJoinPool;",
                    false);
            } else {
                flushPending();
                super.visitFieldInsn(opcode, owner, name, desc);
            }
        }

        @Override public void visitInsn(int opcode)           { flushPending(); super.visitInsn(opcode); }
        @Override public void visitIntInsn(int opcode, int v) { flushPending(); super.visitIntInsn(opcode, v); }
        @Override public void visitTypeInsn(int opcode, String t) { flushPending(); super.visitTypeInsn(opcode, t); }
        @Override public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
            flushPending(); super.visitMethodInsn(opcode, o, n, d, itf);
        }
        @Override public void visitInvokeDynamicInsn(String n, String d, Handle b, Object... a) {
            flushPending(); super.visitInvokeDynamicInsn(n, d, b, a);
        }
        @Override public void visitJumpInsn(int opcode, Label l)    { flushPending(); super.visitJumpInsn(opcode, l); }
        @Override public void visitLdcInsn(Object cst)              { flushPending(); super.visitLdcInsn(cst); }
        @Override public void visitIincInsn(int var, int inc)       { flushPending(); super.visitIincInsn(var, inc); }
        @Override public void visitTableSwitchInsn(int min, int max, Label d, Label... l) {
            flushPending(); super.visitTableSwitchInsn(min, max, d, l);
        }
        @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) {
            flushPending(); super.visitLookupSwitchInsn(d, k, l);
        }
        @Override public void visitMultiANewArrayInsn(String d, int dims) {
            flushPending(); super.visitMultiANewArrayInsn(d, dims);
        }
        @Override public void visitTryCatchBlock(Label s, Label e, Label h, String t) {
            super.visitTryCatchBlock(s, e, h, t);
        }
        @Override public void visitLabel(Label label) {
            flushPending(); super.visitLabel(label);
        }
        @Override public void visitFrame(int t, int nL, Object[] l, int nS, Object[] s) {
            flushPending(); super.visitFrame(t, nL, l, nS, s);
        }
        @Override public void visitLineNumber(int line, Label start) {
            flushPending(); super.visitLineNumber(line, start);
        }
        @Override public void visitMaxs(int maxStack, int maxLocals) {
            flushPending(); super.visitMaxs(maxStack, maxLocals);
        }
        @Override public void visitEnd() {
            flushPending(); super.visitEnd();
        }
    }

    // ── NetworkHandler ────────────────────────────────────────────────

    private static byte[] patchNetworkHandler(byte[] classBytes) {
        var cr = new ClassReader(classBytes);
        var cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {

            // (a) Strip ACC_FINAL from SEND_POOL so we can reassign it
            //     after shutdown.
            @Override
            public FieldVisitor visitField(int access, String name, String desc,
                                            String sig, Object value) {
                if (name.equals("SEND_POOL")) {
                    access &= ~Opcodes.ACC_FINAL;       // clear final
                }
                return super.visitField(access, name, desc, sig, value);
            }

            // (b) Rewrite shutdown() to re-create SEND_POOL after
            //     draining it, instead of leaving it permanently dead.
            //
            //     Before:  SEND_POOL.shutdownNow(); return
            //     After:   SEND_POOL.shutdownNow();
            //              SEND_POOL = createSendPool();
            //              return
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                              String sig, String[] exs) {
                var mv = super.visitMethod(access, name, desc, sig, exs);
                if (name.equals("shutdown") && desc.equals("()V")) {
                    return new ShutdownPatcher(mv);
                }
                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }

    /**
     * Intercepts {@code return} in shutdown() and injects the
     * pool-recreation call just before it.
     */
    private static class ShutdownPatcher extends MethodVisitor {
        ShutdownPatcher(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                // SEND_POOL = createSendPool();
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/ethan/voxyworldgenv2/network/NetworkHandler",
                    "createSendPool",
                    "()Ljava/util/concurrent/ExecutorService;",
                    false);
                super.visitFieldInsn(Opcodes.PUTSTATIC,
                    "com/ethan/voxyworldgenv2/network/NetworkHandler",
                    "SEND_POOL",
                    "Ljava/util/concurrent/ExecutorService;");
            }
            super.visitInsn(opcode);
        }
    }
}
