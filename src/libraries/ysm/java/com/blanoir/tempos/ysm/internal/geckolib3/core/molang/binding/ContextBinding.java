package com.blanoir.tempos.ysm.internal.geckolib3.core.molang.binding;

import com.blanoir.tempos.ysm.internal.molang.runtime.Function;
import com.blanoir.tempos.ysm.internal.molang.runtime.binding.ObjectBinding;

import java.util.HashMap;
import java.util.Map;

/** Registry for game independent math functions. */
public class ContextBinding implements ObjectBinding {
    private final Map<String, Object> entries = new HashMap<>();

    protected void constValue(String name, Object value) {
        entries.put(name, value);
    }

    protected void function(String name, Function value) {
        entries.put(name, value);
    }

    public Object getProperty(String name) {
        return entries.get(name);
    }
}
