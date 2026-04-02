/*
 * Copyright (c) 2017 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.modification;

import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.lang.Lang;
import de.neemann.digital.undo.ModifyException;

import java.awt.*;

/**
 * Modifier to set wire attributes
 */
public class ModifyWireAttributes extends ModificationOfWire {
    private final Color color;

    /**
     * Create a new instance
     *
     * @param wire  the wire to modify
     * @param color the color to set
     */
    public ModifyWireAttributes(Wire wire, Color color) {
        super(wire, Lang.get("mod_setAttributes"));
        this.color = color;
    }

    @Override
    public void modify(Circuit circuit) throws ModifyException {
        try {
            Wire wire = getWire(circuit);
            de.neemann.digital.draw.model.NetList nl = new de.neemann.digital.draw.model.NetList(circuit);
            de.neemann.digital.draw.model.Net net = nl.getNetOfPos(wire.p1);
            if (net != null) {
                for (Wire w : net.getWires()) {
                    w.setCustomColor(color);
                }
            } else {
                wire.setCustomColor(color);
            }
            circuit.updateWireColors();
        } catch (de.neemann.digital.draw.elements.PinException e) {
            throw new ModifyException("internal error", e);
        }
    }
}
