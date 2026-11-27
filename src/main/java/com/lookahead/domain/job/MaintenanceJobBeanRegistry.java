package com.lookahead.domain.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** DLV-970: the one registry; {@link Job} beans are collected at start, later registrations append. */
@Component
public class MaintenanceJobBeanRegistry implements MaintenanceJobRegistry {
    private final List<Job> jobs = new CopyOnWriteArrayList<>();

    public MaintenanceJobBeanRegistry() { }

    @Autowired
    public MaintenanceJobBeanRegistry(ObjectProvider<Job> registered) { registered.orderedStream().forEach(this::register); }

    @Override public void register(Job job) {
        Objects.requireNonNull(job, "job");
        if (job.name() == null || !job.name().matches("[a-z][a-z0-9-]{1,63}")) throw new IllegalArgumentException("Maintenance job names are lower-case words and dashes");
        for (Job known : jobs) if (known.name().equals(job.name())) throw new IllegalArgumentException("Maintenance job " + job.name() + " is already registered");
        jobs.add(job);
    }

    @Override public List<Job> jobs() { return List.copyOf(new ArrayList<>(jobs)); }
}
