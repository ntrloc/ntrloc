package org.ntrloc.graph.db.partition.changelog;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Turns on @Scheduled jobs, for changelog pruning (ChangeLogRetention), the first scheduled job here.
@Configuration
@EnableScheduling
public class ChangeLogConfiguration {
}
