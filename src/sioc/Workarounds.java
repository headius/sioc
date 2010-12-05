/*
 * Copyright (c) 2010, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sioc;

import java.util.*;
import java.dyn.*;
import static java.dyn.MethodType.*;
import java.dyn.MethodHandles.AsInstanceObject;

/* workarounds for current bugs in JSR 292
 */
class Workarounds {
    /* 6983726 remove Proxy from MethodHandles.asInstance SAM conversion */
    public static
    <T> T asInstance(final MethodHandle target, Class<T> samType) {
        try {
            return MethodHandles.asInstance(target, samType);
        } catch (IllegalArgumentException ex) {
            if (samType == Comparator.class && target.type() == MH_Comparator.MH_TYPE)
                return samType.cast(new MH_Comparator(target));
            if (samType == ClassValue.class && target.type() == MH_ClassValue.MH_TYPE)
                return samType.cast(new MH_ClassValue(target));
            throw ex;
        }
    }

    private static class MH_Comparator implements Comparator<Object>, AsInstanceObject {
        final MethodHandle target;
        MH_Comparator(MethodHandle target) {
            this.target = target;
        }

        static final MethodType MH_TYPE
            = methodType(int.class, Object.class, Object.class);
        public int compare(Object o1, Object o2) {
            try {
                return (int) target.invokeExact(o1, o2);
            } catch (Throwable ex) {
                throw unexpectedException(ex);
            }
        }

        public MethodHandle getAsInstanceTarget() { return target; }
        public Class<?> getAsInstanceType() { return Comparator.class; }
    }

    private static class MH_ClassValue extends ClassValue<Object> implements AsInstanceObject {
        final MethodHandle target;
        MH_ClassValue(MethodHandle target) {
            this.target = target;
        }

        static final MethodType MH_TYPE
            = methodType(Object.class, Class.class);
        protected Object computeValue(Class<?> type) {
            try {
                return target.invokeExact(type);
            } catch (Throwable ex) {
                throw unexpectedException(ex);
            }
        }

        public MethodHandle getAsInstanceTarget() { return target; }
        public Class<?> getAsInstanceType() { return ClassValue.class; }
    }

    private static InternalError unexpectedException(Throwable ex) {
        if (ex instanceof RuntimeException)  throw (RuntimeException) ex;
        if (ex instanceof Error)             throw (Error) ex;
        InternalError err = new InternalError("unexpected exception");
        err.initCause(ex);
        return err;
    }
}
