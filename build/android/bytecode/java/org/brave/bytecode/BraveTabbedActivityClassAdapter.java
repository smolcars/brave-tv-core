/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.brave.bytecode;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.F_SAME;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.RETURN;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

public class BraveTabbedActivityClassAdapter extends BraveClassVisitor {
    static String sChromeTabbedActivityClassName =
            "org/chromium/chrome/browser/ChromeTabbedActivity";
    static String sBraveActivityClassName = "org/chromium/chrome/browser/app/BraveActivity";
    static String sTabbedRootUiCoordinatorClassName =
            "org/chromium/chrome/browser/tabbed_mode/TabbedRootUiCoordinator";
    static String sTabbedAppMenuPropertiesDelegateClassName =
            "org/chromium/chrome/browser/tabbed_mode/TabbedAppMenuPropertiesDelegate";
    static String sBraveTabbedAppMenuPropertiesDelegateClassName =
            "org/chromium/chrome/browser/tabbed_mode/BraveTabbedAppMenuPropertiesDelegate";

    static String sChromeTabCreatorClassName =
            "org/chromium/chrome/browser/tabmodel/ChromeTabCreator";
    static String sRedirectTabCreatorClassName =
            "org/chromium/chrome/browser/tabmodel/RedirectTabCreator";
    static String sBraveTabCreatorClassName =
            "org/chromium/chrome/browser/tabmodel/BraveTabCreator";

    private boolean mFoundTabPromoMethod;
    private boolean mFoundMenuActionMethod;

    public BraveTabbedActivityClassAdapter(ClassVisitor visitor) {
        super(visitor);

        changeSuperName(sChromeTabbedActivityClassName, sBraveActivityClassName);

        redirectConstructor(
                sTabbedAppMenuPropertiesDelegateClassName,
                sBraveTabbedAppMenuPropertiesDelegateClassName);

        changeSuperName(sRedirectTabCreatorClassName, sBraveTabCreatorClassName);

        redirectConstructor(sChromeTabCreatorClassName, sBraveTabCreatorClassName);

        makePublicMethod(sChromeTabbedActivityClassName, "hideOverview");

        deleteMethod(sChromeTabbedActivityClassName, "supportsDynamicColors");

        makePublicMethod(sChromeTabbedActivityClassName, "maybeHandleUrlIntent");
        changeMethodOwner(
                sChromeTabbedActivityClassName, "maybeHandleUrlIntent", sBraveActivityClassName);
        makeProtectedField(sChromeTabbedActivityClassName, "mLayoutManager");
        makeProtectedField(sChromeTabbedActivityClassName, "mMultiInstanceManager");
    }

    @Override
    protected MethodVisitor visitMethodImpl(Method method) {
        MethodVisitor visitor = super.visitMethodImpl(method);
        if (sChromeTabbedActivityClassName.equals(mName)
                && "onMenuOrKeyboardAction".equals(method.name)
                && ("(IZLandroid/os/Bundle;"
                                + "Lorg/chromium/components/browser_ui/util/motion/MotionEventInfo;)Z")
                        .equals(method.desc)) {
            mFoundMenuActionMethod = true;
            return new MethodVisitor(ASM7, visitor) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    // The native subclass handles Address before its Brave parent override.
                    super.visitVarInsn(ALOAD, 0);
                    super.visitVarInsn(ILOAD, 1);
                    super.visitMethodInsn(
                            INVOKEVIRTUAL, sBraveActivityClassName,
                            "maybeHandleTvMenuAction", "(I)Z", false);
                    Label nativeAction = new Label();
                    super.visitJumpInsn(IFEQ, nativeAction);
                    super.visitInsn(ICONST_1);
                    super.visitInsn(IRETURN);
                    super.visitLabel(nativeAction);
                    super.visitFrame(F_SAME, 0, null, 0, null);
                }
            };
        }
        if (!sChromeTabbedActivityClassName.equals(mName)
                || !"initiateArchivedTabsAutoDeletePromoManager".equals(method.name)
                || !"()V".equals(method.desc)) {
            return visitor;
        }
        mFoundTabPromoMethod = true;
        return new MethodVisitor(ASM7, visitor) {
            @Override
            public void visitCode() {
                super.visitCode();
                // Suppress the phone promotion without recording a retention decision.
                super.visitMethodInsn(
                        INVOKESTATIC, "org/chromium/base/DeviceInfo", "isTV", "()Z", false);
                Label phone = new Label();
                super.visitJumpInsn(IFEQ, phone);
                super.visitInsn(RETURN);
                super.visitLabel(phone);
                super.visitFrame(F_SAME, 0, null, 0, null);
            }
        };
    }

    @Override
    public void visitEnd() {
        if (sChromeTabbedActivityClassName.equals(mName) && !mFoundTabPromoMethod) {
            throw new IllegalStateException("TV tab-promotion method missing");
        }
        if (sChromeTabbedActivityClassName.equals(mName) && !mFoundMenuActionMethod) {
            throw new IllegalStateException("TV menu-action method missing");
        }
        super.visitEnd();
    }
}
