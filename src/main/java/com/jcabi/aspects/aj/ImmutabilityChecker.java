/**
 * Copyright (c) 2012-2014, jcabi.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the jcabi.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jcabi.aspects.aj;

import com.jcabi.aspects.Immutable;
import com.jcabi.log.Logger;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;

/**
 * Checks for class immutability.
 *
 * <p>The class is thread-safe.
 *
 * @author Yegor Bugayenko (yegor@tpc2.com)
 * @version $Id$
 * @since 0.7.8
 */
@Aspect
public final class ImmutabilityChecker {

    /**
     * Already checked immutable classes.
     */
    private final transient Set<Class<?>> immutable = new HashSet<Class<?>>();

    /**
     * Catch instantiation and validate class.
     *
     * <p>Try NOT to change the signature of this method, in order to keep
     * it backward compatible.
     *
     * @param point Joint point
     */
    @After("initialization((@com.jcabi.aspects.Immutable *).new(..))")
    public void after(final JoinPoint point) {
        final Object object = point.getTarget();
        final Class<?> type = object.getClass();
        try {
            this.check(object, type);
        } catch (final ImmutabilityChecker.Violation ex) {
            throw new IllegalStateException(
                String.format(
                    // @checkstyle LineLength (1 line)
                    "%s is not immutable, can't use it (jcabi-aspects ${project.version}/${buildNumber})",
                    type
                ),
                ex
            );
        }
    }

    /**
     * This class is immutable?
     * @param obj The object to check
     * @param type The class to check
     * @throws ImmutabilityChecker.Violation If it is mutable
     */
    private void check(final Object obj, final Class<?> type)
        throws ImmutabilityChecker.Violation {
        synchronized (this.immutable) {
            if (!this.ignore(type)) {
                if (type.isInterface()
                    && !type.isAnnotationPresent(Immutable.class)) {
                    throw new ImmutabilityChecker.Violation(
                        String.format(
                            "Interface '%s' is not annotated with @Immutable",
                            type.getName()
                        )
                    );
                }
                if (!type.isInterface()
                    && !Modifier.isFinal(type.getModifiers())) {
                    throw new Violation(
                        String.format(
                            "Class '%s' is not final",
                            type.getName()
                        )
                    );
                }
                try {
                    this.fields(obj, type);
                } catch (final ImmutabilityChecker.Violation ex) {
                    throw new ImmutabilityChecker.Violation(
                        String.format("Class '%s' is mutable", type.getName()),
                        ex
                    );
                }
                this.immutable.add(type);
                Logger.debug(this, "#check(%s): immutability checked", type);
            }
        }
    }

    /**
     * This array field immutable?
     * @param obj The object which has the array
     * @param field The field to check
     * @throws Violation If it is mutable.
     */
    private void checkArray(final Object obj, final Field field)
        throws Violation {
        field.setAccessible(true);
        if (field.isAnnotationPresent(Immutable.Array.class)) {
            try {
                this.check(field.get(obj), field.getType().getComponentType());
            } catch (final ImmutabilityChecker.Violation ex) {
                throw new ImmutabilityChecker.Violation(
                    String.format(
                        "Field array component type '%s' is mutable",
                        field.getType().getComponentType().getName()
                    ),
                    ex
                );
            } catch (final IllegalAccessException ex) {
                this.throwViolationFieldNotAccessible(field);
            }
        } else {
            // @checkstyle LineLength (3 lines)
            throw new ImmutabilityChecker.Violation(
                String.format(
                    "Field '%s' is an array and is not annotated with @Immutable.Array",
                    field.getName()
                )
            );
        }
    }

    /**
     * This class should be ignored and never checked any more?
     * @param type The type to check
     * @return TRUE if this class shouldn't be checked
     */
    private boolean ignore(final Class<?> type) {
        // @checkstyle BooleanExpressionComplexity (5 lines)
        return type.getName().startsWith("java.lang.")
            || type.isPrimitive()
            || type.getName().startsWith("org.aspectj.runtime.reflect.")
            || this.immutable.contains(type);
    }

    /**
     * All its fields are safe?
     * @param obj The object to check
     * @param type Type to check
     * @throws ImmutabilityChecker.Violation If it is mutable
     */
    private void fields(final Object obj, final Class<?> type)
        throws ImmutabilityChecker.Violation {
        final Field[] fields = type.getDeclaredFields();
        for (int pos = 0; pos < fields.length; ++pos) {
            final Field field = fields[pos];
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!Modifier.isFinal(field.getModifiers())) {
                throw new ImmutabilityChecker.Violation(
                    String.format(
                        "field '%s' is not final",
                        field
                    )
                );
            }
            try {
                this.checkDeclaredAndActualTypes(obj, field, type);
                if (field.getType().isArray()) {
                    this.checkArray(obj, field);
                }
            } catch (final ImmutabilityChecker.Violation ex) {
                throw new ImmutabilityChecker.Violation(
                    String.format(
                        "field '%s' is mutable",
                        field
                    ),
                    ex
                );
            }
        }
    }

    /**
     * Checks if both declared and actual types of the field within the object
     * are immutable.
     *
     * @param obj The given object
     * @param field The given field
     * @param type The type to be skipped
     * @throws ImmutabilityChecker.Violation If they are mutable
     */
    private void checkDeclaredAndActualTypes(final Object obj,
        final Field field, final Class<?> type)
        throws ImmutabilityChecker.Violation {
        field.setAccessible(true);
        if (field.getType() != type) {
            this.check(obj, field.getType());
        }
        Object fieldValue = null;
        try {
            fieldValue = field.get(obj);
        } catch (final IllegalAccessException ex) {
            this.throwViolationFieldNotAccessible(field);
        }
        if (fieldValue != null
            && !field.getType().equals(fieldValue.getClass())) {
            this.check(obj, fieldValue.getClass());
        }
    }

    /**
     * Throws an {@link Violation} exception with text about unaccessibility of
     * the field.
     *
     * @param field The field
     * @throws ImmutabilityChecker.Violation Always
     */
    private void throwViolationFieldNotAccessible(final Field field)
        throws ImmutabilityChecker.Violation {
        throw new ImmutabilityChecker.Violation(
            String.format(
                "Field '%s' is not accessible",
                field
            )
        );
    }

    /**
     * Immutability violation.
     */
    private static final class Violation extends Exception {
        /**
         * Serialization marker.
         */
        private static final long serialVersionUID = 1L;
        /**
         * Public ctor.
         * @param msg Message
         */
        public Violation(final String msg) {
            super(msg);
        }
        /**
         * Public ctor.
         * @param msg Message
         * @param cause Cause of it
         */
        public Violation(final String msg,
            final ImmutabilityChecker.Violation cause) {
            super(msg, cause);
        }
    }

}
