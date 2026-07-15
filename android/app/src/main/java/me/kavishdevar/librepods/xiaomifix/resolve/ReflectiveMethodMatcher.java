package me.kavishdevar.librepods.xiaomifix.resolve;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ReflectiveMethodMatcher {
    private ReflectiveMethodMatcher() {
    }

    public static Method findMethod(Class<?> clazz, Class<?>[] paramTypes) {
        return findMethod(clazz, paramTypes, null);
    }

    public static Method findMethod(Class<?> clazz, Class<?>[] paramTypes, Class<?> returnType) {
        if (clazz == null) {
            return null;
        }
        Class<?> cur = clazz;
        while (cur != null) {
            for (Method method : cur.getDeclaredMethods()) {
                if (!matchesParams(method, paramTypes)) {
                    continue;
                }
                if (returnType != null && !returnType.equals(method.getReturnType())) {
                    continue;
                }
                method.setAccessible(true);
                return method;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    public static List<Method> findAllDeclaredMethods(
            Class<?> clazz, Class<?>[] paramTypes, Class<?> returnType) {
        if (clazz == null) {
            return Collections.emptyList();
        }
        List<Method> matches = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!matchesParams(method, paramTypes)) {
                continue;
            }
            if (returnType != null && !returnType.equals(method.getReturnType())) {
                continue;
            }
            method.setAccessible(true);
            matches.add(method);
        }
        return matches;
    }

    public static Method findDeclaredMethod(
            Class<?> clazz, String name, Class<?>[] paramTypes, Class<?> returnType) {
        if (clazz == null || name == null) {
            return null;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            if (!matchesParams(method, paramTypes)) {
                continue;
            }
            if (returnType != null && !returnType.equals(method.getReturnType())) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    public static Method findDeclaredMethod(Class<?> clazz, Class<?>[] paramTypes, Class<?> returnType) {
        if (clazz == null) {
            return null;
        }
        Method match = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!matchesParams(method, paramTypes)) {
                continue;
            }
            if (returnType != null && !returnType.equals(method.getReturnType())) {
                continue;
            }
            if (match != null) {
                return null;
            }
            method.setAccessible(true);
            match = method;
        }
        return match;
    }

    public static List<Method> findAllMethods(Class<?> clazz, Class<?>[] paramTypes, Class<?> returnType) {
        return findAllDeclaredMethods(clazz, paramTypes, returnType);
    }

    public static Method findSingleObjectArgMethod(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        Method match = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 1) {
                continue;
            }
            if (!Object.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers()) && !Modifier.isProtected(method.getModifiers())) {
                continue;
            }
            if (match != null) {
                return null;
            }
            method.setAccessible(true);
            match = method;
        }
        return match;
    }

    private static boolean matchesParams(Method method, Class<?>[] paramTypes) {
        Class<?>[] actual = method.getParameterTypes();
        if (actual.length != paramTypes.length) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            if (!paramTypes[i].isAssignableFrom(actual[i])) {
                return false;
            }
        }
        return true;
    }
}
