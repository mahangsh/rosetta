package com.adaptivescale.rosetta.ddl.targets.kinetica.decorators;

import com.adaptivescale.rosetta.common.models.Column;
import com.adaptivescale.rosetta.ddl.targets.ColumnDataTypeName;

import static com.adaptivescale.rosetta.ddl.targets.kinetica.Constants.PRECISION_DEFAULTS;
import static com.adaptivescale.rosetta.ddl.targets.kinetica.Constants.PRECISION_TYPES;

public class KineticaColumnTypeName implements ColumnDataTypeName {
    @Override
    public String nameForColumn(Column column) {
        StringBuilder builder = new StringBuilder();
        builder.append(ColumnDataTypeName.super.nameForColumn(column));
        if ( !PRECISION_DEFAULTS.contains(column.getPrecision()) && PRECISION_TYPES.contains(column.getTypeName().toLowerCase())) {
            builder.append("(").append(column.getPrecision()).append(")");
        }
        return builder.toString();
    }
}
