package com.blanoir.moons.ysm.internal.molang;

import com.blanoir.moons.ysm.internal.molang.parser.MolangParser;
import com.blanoir.moons.ysm.internal.molang.parser.ast.Expression;
import com.blanoir.moons.ysm.internal.molang.runtime.binding.ObjectBinding;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

public final class MolangEngineImpl implements MolangEngine {

    private final ObjectBinding bindings;

    MolangEngineImpl(ObjectBinding bindings) {
        this.bindings = bindings;
    }

    @Override
    public List<Expression> parse(Reader reader) throws IOException {
        return MolangParser.parser(reader, this.bindings).parseAll();
    }
}
