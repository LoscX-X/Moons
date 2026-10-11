package com.blanoir.moons.client.access;

import com.blanoir.moons.api.mapping.GameNames;

import java.lang.invoke.*;

/** Resolves named private members in both the developer and Fabric production namespaces. */
final class GameLookup {
    static Lookup privateLookupIn(Class<?> owner, MethodHandles.Lookup caller)
            throws IllegalAccessException {
        return new Lookup(MethodHandles.privateLookupIn(owner, caller));
    }

    record Lookup(MethodHandles.Lookup delegate) {
        VarHandle findVarHandle(Class<?> owner, String name, Class<?> type)
                throws ReflectiveOperationException {
            return delegate.findVarHandle(owner, GameNames.field(owner, name, type), type);
        }

        VarHandle findStaticVarHandle(Class<?> owner, String name, Class<?> type)
                throws ReflectiveOperationException {
            return delegate.findStaticVarHandle(owner, GameNames.field(owner, name, type), type);
        }

        MethodHandle findVirtual(Class<?> owner, String name, MethodType type)
                throws ReflectiveOperationException {
            return delegate.findVirtual(owner, GameNames.method(owner, name, type), type);
        }

        MethodHandle findStatic(Class<?> owner, String name, MethodType type)
                throws ReflectiveOperationException {
            return delegate.findStatic(owner, GameNames.method(owner, name, type), type);
        }

        MethodHandle findConstructor(Class<?> owner, MethodType type)
                throws ReflectiveOperationException {
            return delegate.findConstructor(owner, type);
        }
    }
}
