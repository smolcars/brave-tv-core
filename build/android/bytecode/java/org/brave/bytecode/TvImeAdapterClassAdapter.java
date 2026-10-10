// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.brave.bytecode;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

/** Observe renderer state without changing the physical IME's behavior. */
final class TvImeAdapterClassAdapter extends BraveClassVisitor {
    private static final String IME = "org/chromium/content/browser/input/ImeAdapterImpl";
    private static final String TARGET = "org/chromium/content/browser/input/TvTextInput";
    private int mHooks;

    TvImeAdapterClassAdapter(ClassVisitor visitor) {
        super(visitor);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!IME.equals(mName)) return visitor;
        boolean state = name.equals("updateState");
        boolean focus = name.equals("focusedNodeChanged");
        boolean reset = name.equals("resetAndHideKeyboard") || name.equals("destroyFromNative");
        if (!state && !focus && !reset) return visitor;
        String expected =
                state
                        ? "(IIIIZZLjava/lang/String;IIIIZII[Lorg/chromium/content/browser/input/ImeTextSpan;)V"
                        : focus ? "(ZIIII)V" : "()V";
        if (!descriptor.equals(expected))
            throw new IllegalStateException("TV IME signature changed: " + name);
        mHooks++;
        return new MethodVisitor(ASM7, visitor) {
            @Override
            public void visitCode() {
                super.visitCode();
                if (!state) {
                    super.visitVarInsn(ALOAD, 0);
                    super.visitMethodInsn(
                            INVOKESTATIC, TARGET, "focusChanged", "(L" + IME + ";)V", false);
                }
            }

            @Override
            public void visitInsn(int opcode) {
                if (state && opcode == RETURN) {
                    super.visitVarInsn(ALOAD, 0);
                    super.visitVarInsn(ILOAD, 1);
                    super.visitVarInsn(ILOAD, 2);
                    super.visitVarInsn(ALOAD, 7);
                    super.visitVarInsn(ILOAD, 8);
                    super.visitVarInsn(ILOAD, 9);
                    super.visitMethodInsn(
                            INVOKESTATIC,
                            TARGET,
                            "stateChanged",
                            "(L" + IME + ";IILjava/lang/String;II)V",
                            false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    @Override
    public void visitEnd() {
        if (IME.equals(mName) && mHooks != 4)
            throw new IllegalStateException("TV IME hooks missing");
        super.visitEnd();
    }
}
