package com.blanoir.moons.ysm.internal.molang.parser.ast;

import com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class StringExpression implements Expression {

    private final String name;

    private final int path;

    public StringExpression(@NotNull String str) {
        this.name = Objects.requireNonNull(str, "value");
        this.path = StringPool.computeIfAbsent(str);
    }

    @NotNull
    public String getName() {
        return this.name;
    }

    public int getPath() {
        return this.path;
    }

    @Override
    public <R> R visit(@NotNull ExpressionVisitor<R> expressionVisitor) {
        return expressionVisitor.visitString(this);
    }

    public String toString() {
        return this.name;
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof String) {
            return this.name.equals(obj);
        }
        return (obj instanceof StringExpression) && this.path == ((StringExpression) obj).path;
    }

    public int hashCode() {
        return this.name.hashCode();
    }
}
