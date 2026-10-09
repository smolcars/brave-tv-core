// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.brave.bytecode;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.F_FULL;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/** Selects the lightweight TV home before upstream new-tab services initialize. */
final class TvNativePageClassAdapter extends BraveClassVisitor {
    private static final String FACTORY =
            "org/chromium/chrome/browser/native_page/NativePageFactory";
    private static final String BUILDER = FACTORY + "$NativePageBuilder";
    private static final String HOST = "org/chromium/chrome/browser/ui/native_page/NativePageHost";
    private static final String PAGE = "org/chromium/chrome/browser/ui/native_page/NativePage";
    private static final String TAB = "org/chromium/chrome/browser/tab/Tab";

    private boolean mFoundMethod;

    TvNativePageClassAdapter(ClassVisitor visitor) {
        super(visitor);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!BUILDER.equals(mName) || !"buildNewTabPage".equals(name)) return visitor;
        mFoundMethod = true;
        if (!("(L" + TAB + ";Ljava/lang/String;)L" + PAGE + ";").equals(descriptor)) {
            throw new IllegalStateException("TV native-page factory signature changed");
        }
        return new MethodVisitor(ASM7, visitor) {
            private int mHooks;

            @Override
            public void visitMethodInsn(
                    int opcode, String owner, String method, String desc, boolean isInterface) {
                super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                if (opcode != INVOKESPECIAL
                        || !(FACTORY + "$TabShim").equals(owner)
                        || !"<init>".equals(method)) return;
                mHooks++;
                // Keep the original host on the stack for the non-TV path.
                super.visitInsn(DUP);
                super.visitVarInsn(ALOAD, 1);
                super.visitMethodInsn(
                        INVOKESTATIC,
                        "org/chromium/chrome/browser/tv/TvHomePage",
                        "createIfTelevision",
                        "(L" + HOST + ";L" + TAB + ";)L" + PAGE + ";",
                        false);
                super.visitInsn(DUP);
                Label fallback = new Label();
                super.visitJumpInsn(IFNULL, fallback);
                super.visitInsn(ARETURN);
                super.visitLabel(fallback);
                super.visitFrame(
                        F_FULL,
                        3,
                        new Object[] {BUILDER, TAB, "java/lang/String"},
                        2,
                        new Object[] {FACTORY + "$TabShim", PAGE});
                super.visitInsn(POP);
            }

            @Override
            public void visitEnd() {
                if (mHooks != 1) {
                    throw new IllegalStateException("TV native-page host construction changed");
                }
                super.visitEnd();
            }
        };
    }

    @Override
    public void visitEnd() {
        if (BUILDER.equals(mName) && !mFoundMethod) {
            throw new IllegalStateException("TV native-page factory method missing");
        }
        super.visitEnd();
    }
}
