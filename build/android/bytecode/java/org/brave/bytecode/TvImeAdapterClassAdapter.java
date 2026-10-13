// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.brave.bytecode;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.F_SAME;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/** Observe renderer state and yield the physical IME while a phone owns typing. */
final class TvImeAdapterClassAdapter extends BraveClassVisitor {
    private static final String IME = "org/chromium/content/browser/input/ImeAdapterImpl";
    private static final String TARGET = "org/chromium/content/browser/input/TvTextInput";
    private int mHooks;
    private int mInputGates;
    private int mRestartFields;

    TvImeAdapterClassAdapter(ClassVisitor visitor) {
        super(visitor);
    }

    @Override
    public FieldVisitor visitField(
            int access, String name, String descriptor, String signature, Object value) {
        if (IME.equals(mName) && name.equals("mRestartInputOnNextStateUpdate")) {
            if (!descriptor.equals("Z"))
                throw new IllegalStateException("TV IME restart field changed");
            mRestartFields++;
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!IME.equals(mName)) return visitor;
        boolean state = name.equals("updateState");
        boolean focus = name.equals("focusedNodeChanged");
        boolean reset = name.equals("resetAndHideKeyboard") || name.equals("destroyFromNative");
        boolean show = name.equals("showSoftKeyboard");
        boolean connection =
                name.equals("onCreateInputConnection")
                        && descriptor.equals(
                                "(Landroid/view/inputmethod/EditorInfo;Z)Lorg/chromium/content/browser/input/ChromiumBaseInputConnection;");
        if (!state && !focus && !reset && !show && !connection) return visitor;
        String expected =
                state
                        ? "(IIIIZZLjava/lang/String;IIIIZII[Lorg/chromium/content/browser/input/ImeTextSpan;)V"
                        : focus ? "(ZIIII)V" : connection ? descriptor : "()V";
        if (!descriptor.equals(expected))
            throw new IllegalStateException("TV IME signature changed: " + name);
        mHooks++;
        return new MethodVisitor(ASM7, visitor) {
            @Override
            public void visitCode() {
                super.visitCode();
                if (focus || reset) {
                    super.visitVarInsn(ALOAD, 0);
                    super.visitMethodInsn(
                            INVOKESTATIC, TARGET, "focusChanged", "(L" + IME + ";)Z", false);
                    if (focus) {
                        Label unchanged = new Label();
                        super.visitJumpInsn(IFEQ, unchanged);
                        super.visitVarInsn(ALOAD, 0);
                        super.visitInsn(ICONST_1);
                        super.visitFieldInsn(PUTFIELD, IME, "mRestartInputOnNextStateUpdate", "Z");
                        super.visitLabel(unchanged);
                        super.visitFrame(F_SAME, 0, null, 0, null);
                    } else {
                        super.visitInsn(POP);
                    }
                } else if (show) {
                    super.visitVarInsn(ALOAD, 0);
                    super.visitMethodInsn(
                            INVOKESTATIC, TARGET, "phoneTyping", "(L" + IME + ";)Z", false);
                    Label physical = new Label();
                    super.visitJumpInsn(IFEQ, physical);
                    super.visitInsn(RETURN);
                    super.visitLabel(physical);
                    super.visitFrame(F_SAME, 0, null, 0, null);
                }
            }

            @Override
            public void visitMethodInsn(
                    int opcode, String owner, String method, String desc, boolean isInterface) {
                if (connection
                        && owner.equals(IME)
                        && method.equals("focusedNodeEditable")
                        && desc.equals("()Z")) {
                    mInputGates++;
                    super.visitInsn(DUP);
                    super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                    super.visitMethodInsn(
                            INVOKESTATIC, TARGET, "allowPhysicalInput", "(L" + IME + ";Z)Z", false);
                    return;
                }
                super.visitMethodInsn(opcode, owner, method, desc, isInterface);
            }

            @Override
            public void visitInsn(int opcode) {
                if (state && opcode == RETURN) {
                    super.visitVarInsn(ALOAD, 0);
                    super.visitVarInsn(ILOAD, 1);
                    super.visitVarInsn(ILOAD, 2);
                    super.visitVarInsn(ILOAD, 3);
                    super.visitVarInsn(ILOAD, 4);
                    super.visitVarInsn(ALOAD, 7);
                    super.visitVarInsn(ILOAD, 8);
                    super.visitVarInsn(ILOAD, 9);
                    super.visitVarInsn(ILOAD, 10);
                    super.visitVarInsn(ILOAD, 11);
                    super.visitMethodInsn(
                            INVOKESTATIC,
                            TARGET,
                            "stateChanged",
                            "(L" + IME + ";IIIILjava/lang/String;IIII)V",
                            false);
                }
                super.visitInsn(opcode);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                // The RETURN hook pushes the adapter and nine state arguments.
                super.visitMaxs(
                        state
                                ? Math.max(maxStack, 10)
                                : connection ? maxStack + 1 : Math.max(maxStack, focus ? 2 : 1),
                        maxLocals);
            }
        };
    }

    @Override
    public void visitEnd() {
        if (IME.equals(mName) && (mHooks != 6 || mInputGates != 1 || mRestartFields != 1))
            throw new IllegalStateException("TV IME hooks missing");
        super.visitEnd();
    }
}
