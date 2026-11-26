package dev.agentworkshq.eval.core.testsupport;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class ToStringCheck {

    private ToStringCheck() {
    }

    // every public class and record in the main classes that hold the anchor
    public static List<Class<?>> publicTypes(Class<?> anchor) {
        List<Class<?>> types = new ArrayList<>();
        try {
            Path root = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(root)) {
                throw new AssertionError("the main classes must be a directory, run the test with Maven");
            }
            List<String> names;
            try (Stream<Path> files = Files.walk(root)) {
                names = files.map(p -> root.relativize(p).toString())
                        .filter(n -> n.endsWith(".class"))
                        .map(n -> n.substring(0, n.length() - ".class".length()).replace('\\', '.').replace('/', '.'))
                        .sorted()
                        .toList();
            }
            for (String name : names) {
                Class<?> type = Class.forName(name, false, anchor.getClassLoader());
                if (isPublic(type) && !type.isInterface() && !type.isEnum()) {
                    types.add(type);
                }
            }
        } catch (IOException | URISyntaxException | ClassNotFoundException e) {
            throw new AssertionError("could not list the main classes", e);
        }
        return types;
    }

    public static List<Class<?>> unprotected(List<Class<?>> types, Map<Class<?>, String> allowed) {
        return types.stream()
                .filter(t -> holdsText(t) && !declaresToString(t) && !allowed.containsKey(t))
                .toList();
    }

    // an entry is only needed for a checked type that holds text and keeps Object.toString
    public static List<Class<?>> staleEntries(List<Class<?>> types, Map<Class<?>, String> allowed) {
        return allowed.keySet().stream()
                .filter(t -> !types.contains(t) || !holdsText(t) || !keepsObjectToString(t))
                .sorted(Comparator.<Class<?>, String>comparing(Class::getName))
                .toList();
    }

    public static boolean holdsText(Class<?> type) {
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getGenericType)
                    .anyMatch(ToStringCheck::carriesText);
        }
        // fields of package private parents are included because the parent is not checked by itself
        for (Class<?> c = type; c != null && (c == type || !Modifier.isPublic(c.getModifiers())); c = c.getSuperclass()) {
            if (Arrays.stream(c.getDeclaredFields())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .map(Field::getGenericType)
                    .anyMatch(ToStringCheck::carriesText)) {
                return true;
            }
        }
        return false;
    }

    // the toString that the compiler generates for a record is final, a written one is not
    public static boolean declaresToString(Class<?> type) {
        try {
            Method method = type.getDeclaredMethod("toString");
            return !type.isRecord() || !Modifier.isFinal(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean keepsObjectToString(Class<?> type) {
        try {
            return type.getMethod("toString").getDeclaringClass() == Object.class;
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isPublic(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                return false;
            }
        }
        return !type.isAnonymousClass() && !type.isLocalClass();
    }

    private static boolean carriesText(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (Map.class.isAssignableFrom(raw)) {
                return true;
            }
            return Collection.class.isAssignableFrom(raw)
                    && Arrays.stream(parameterized.getActualTypeArguments()).anyMatch(ToStringCheck::carriesText);
        }
        if (type instanceof GenericArrayType array) {
            return carriesText(array.getGenericComponentType());
        }
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                return carriesText(Array.newInstance(c.getComponentType(), 0).getClass().getComponentType());
            }
            return c == Object.class || c == CharSequence.class || c == String.class || c.isRecord()
                    || Map.class.isAssignableFrom(c) || Collection.class.isAssignableFrom(c);
        }
        // wildcards and type variables could stand for anything
        return true;
    }
}
