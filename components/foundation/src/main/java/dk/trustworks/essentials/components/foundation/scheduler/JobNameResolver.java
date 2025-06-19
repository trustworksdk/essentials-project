package dk.trustworks.essentials.components.foundation.scheduler;


import dk.trustworks.essentials.shared.network.Network;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class JobNameResolver {

    public static final String UNDER_SCORE  = "_";

    public static String resolve(String name) {
        requireNonNull(name, "name cannot be null");
        String instanceId = Network.hostName();
        String suffix = UNDER_SCORE + instanceId;
        return name.endsWith(suffix)
               ? name
               : name + suffix;
    }
}
