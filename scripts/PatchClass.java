import org.objectweb.asm.*;
import java.nio.file.*;

public class PatchClass {
    public static void main(String[] args) throws Exception {
        var in = Paths.get(args[0]);
        var out = Paths.get(args[1]);
        var bytes = Files.readAllBytes(in);

        var cr = new ClassReader(bytes);
        var cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                              String sig, String[] exs) {
                var mv = super.visitMethod(access, name, desc, sig, exs);
                if (name.equals("lambda$workerLoop$6")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                            if (n.equals("whenCompleteAsync") && d.equals("(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")) {
                                super.visitInsn(Opcodes.POP); // drop executor arg
                                super.visitMethodInsn(opcode, owner, "whenComplete",
                                    "(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;", itf);
                            } else {
                                super.visitMethodInsn(opcode, owner, n, d, itf);
                            }
                        }
                    };
                }
                return mv;
            }
        }, 0);

        Files.write(out, cw.toByteArray());
        System.out.println("Patched: " + in + " -> " + out);
    }
}
