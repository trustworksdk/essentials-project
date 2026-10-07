package com.example.orders;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;

class QueueConfiguration {
    PostgresqlDurableQueues durableQueues(Object unitOfWorkFactory) {
        var serializer = EssentialsJSONEventSerializers.createForActiveJacksonFlavor();
        var bus = new LocalCommandBus();
        return PostgresqlDurableQueues.builder()
                                      .setJsonSerializer(serializer)
                                      .setUseOrderedUnorderedQuery(true)
                                      .build();
    }
}
