package org.example.diagnosis.fault;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class EvalRecordStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final List<Map<String, Object>> records = new CopyOnWriteArrayList<>();

    public void add(Map<String, Object> record) {
        Map<String, Object> copy = new LinkedHashMap<>(record);
        copy.putIfAbsent("at", LocalDateTime.now().format(FMT));
        records.add(copy);
    }

    public List<Map<String, Object>> history() {
        return new ArrayList<>(records);
    }
}
