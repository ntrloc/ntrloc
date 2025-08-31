package org.ntrloc.graph.db.language;

import java.util.Date;

public class DateProperty implements ScalarProperty<Date, Long> {

    private String name;
    private Date value;

    public DateProperty(String name, Date value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Long getValue() {
        return value.getTime();
    }

}
