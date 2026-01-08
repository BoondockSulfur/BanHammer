package dev.banhammer.plugin.util;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Utility class for reflection-based method invocation.
 * Provides compatibility layer for accessing methods across different API versions.
 *
 * @since 3.0.0
 */
public final class ReflectionUtil {

    private ReflectionUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Invokes a no-argument method on the given object, trying multiple method names.
     * Returns the first successfully invoked method's return value.
     *
     * @param obj The object to invoke the method on
     * @param methodNames Method names to try, in order of preference
     * @return The method's return value, or null if no method was found or invocation failed
     */
    public static Object invokeNoArg(Object obj, String... methodNames) {
        if (obj == null || methodNames == null || methodNames.length == 0) {
            return null;
        }

        Class<?> clazz = obj.getClass();
        for (String methodName : methodNames) {
            try {
                Method method = clazz.getMethod(methodName);
                return method.invoke(obj);
            } catch (Throwable ignored) {
                // Try next method name
            }
        }
        return null;
    }

    /**
     * Attempts to invoke a no-argument method and cast the result to Boolean.
     *
     * @param obj The object to invoke the method on
     * @param methodNames Method names to try
     * @return The result as Boolean, or false if invocation failed
     */
    public static boolean invokeBoolean(Object obj, String... methodNames) {
        Object result = invokeNoArg(obj, methodNames);
        return result instanceof Boolean b ? b : false;
    }

    /**
     * Attempts to invoke a no-argument method and cast the result to Integer.
     *
     * @param obj The object to invoke the method on
     * @param methodNames Method names to try
     * @return The result as int, or 0 if invocation failed
     */
    public static int invokeInt(Object obj, String... methodNames) {
        Object result = invokeNoArg(obj, methodNames);
        return result instanceof Number n ? n.intValue() : 0;
    }

    /**
     * Attempts to invoke a no-argument method and cast the result to Double.
     *
     * @param obj The object to invoke the method on
     * @param methodNames Method names to try
     * @return The result as double, or 0.0 if invocation failed
     */
    public static double invokeDouble(Object obj, String... methodNames) {
        Object result = invokeNoArg(obj, methodNames);
        return result instanceof Number n ? n.doubleValue() : 0.0;
    }

    /**
     * Attempts to invoke a no-argument method and cast the result to Long.
     *
     * @param obj The object to invoke the method on
     * @param methodNames Method names to try
     * @return The result as long, or 0L if invocation failed
     */
    public static long invokeLong(Object obj, String... methodNames) {
        Object result = invokeNoArg(obj, methodNames);
        return result instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * Attempts to invoke a no-argument method and convert the result to String.
     *
     * @param obj The object to invoke the method on
     * @param methodNames Method names to try
     * @return The result as String, or null if invocation failed
     */
    public static String invokeString(Object obj, String... methodNames) {
        Object result = invokeNoArg(obj, methodNames);
        return result != null ? result.toString() : null;
    }

    /**
     * Checks if a class has a method with the given name and parameter types.
     *
     * @param clazz The class to check
     * @param methodName The method name
     * @param parameterTypes The parameter types
     * @return true if the method exists, false otherwise
     */
    public static boolean hasMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            clazz.getMethod(methodName, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Attempts to get a field value from an object using reflection.
     *
     * @param obj The object
     * @param fieldName The field name
     * @return Optional containing the field value, or empty if failed
     */
    public static Optional<Object> getFieldValue(Object obj, String fieldName) {
        if (obj == null || fieldName == null) {
            return Optional.empty();
        }

        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return Optional.ofNullable(field.get(obj));
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /**
     * Attempts to set a field value on an object using reflection.
     *
     * @param obj The object
     * @param fieldName The field name
     * @param value The value to set
     * @return true if successful, false otherwise
     */
    public static boolean setFieldValue(Object obj, String fieldName, Object value) {
        if (obj == null || fieldName == null) {
            return false;
        }

        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
