import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Patches Voxy World Gen V2's ChunkGenerationManager to fix c2me deadlock.
 *
 * In lambda$workerLoop$6, changes:
 *   whenCompleteAsync(action, server)
 * to:
 *   whenComplete(action)
 *
 * This prevents the callback from being queued on the server thread (which
 * enters c2me's managedBlock and deadlocks). The callback runs on the
 * completing thread instead.
 */
public class PatchVWGV2 {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java PatchVWGV2 <input.jar> <output.jar>");
            System.exit(1);
        }

        var inJar = Paths.get(args[0]);
        var outJar = Paths.get(args[1]);

        var targetClass = "com/ethan/voxyworldgenv2/core/ChunkGenerationManager.class";

        try (var zos = new ZipOutputStream(Files.newOutputStream(outJar));
             var zis = new ZipInputStream(Files.newInputStream(inJar))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                var name = entry.getName();
                var bytes = zis.readAllBytes();

                if (name.equals(targetClass)) {
                    System.out.println("Patching: " + name);
                    bytes = patchWhenCompleteAsync(bytes);
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

    private static byte[] patchWhenCompleteAsync(byte[] classBytes) {
        var cr = new ClassReader(classBytes);
        var cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                              String sig, String[] exs) {
                var mv = super.visitMethod(access, name, desc, sig, exs);
                if (name.equals("lambda$workerLoop$6")) {
                    return new WhenCompleteAsyncPatcher(mv);
                }
                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }

    private static class WhenCompleteAsyncPatcher extends MethodVisitor {
        WhenCompleteAsyncPatcher(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (name.equals("whenCompleteAsync")
                && desc.equals("(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")) {

                // stack before: [future, action, executor]
                // pop executor, leaving [future, action] for whenComplete
                super.visitInsn(Opcodes.POP);
                super.visitMethodInsn(opcode, owner, "whenComplete",
                    "(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;", itf);
            } else {
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
        }
    }
}
