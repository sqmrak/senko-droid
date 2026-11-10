package com.senko.droid;

import java.util.ArrayList;
import java.util.List;

/** what the engine reports, as the screens use it */
final class Model {
    private Model() {}

    static final class Server {
        int index;
        boolean selected;
        int group;
        String proto = "";
        String net = "";
        String security = "";
        boolean supported;
        String host = "";
        int port;
        /** the config of a json feed this node belongs to, 0 for a node that stands alone */
        int profile;
        String remark = "";
        /** the share link, empty until asked for with GETSRV */
        String link = "";
        /** a hash of the link without its name, the same for the same server in any list */
        String identity = "";
    }

    static final class Sub {
        int index;
        String name = "";
        String url = "";
        String header = "";
        long expire;
        long upload;
        long download;
        long total;
        String description = "";
        String supportUrl = "";
        /** what the panel's headers said: its account page, its notice, the refresh pace it asks
         * for in hours and the second its traffic counter resets, empty or 0 when it said nothing */
        String webPageUrl = "";
        String announce = "";
        int updateHours;
        long refillDate;
    }

    static final class Catalog {
        final List<Server> servers = new ArrayList<Server>();
        /** in the order the engine shows them */
        final List<Sub> subs = new ArrayList<Sub>();
        /** subscription indexes in display order, -1 stands for the manual section */
        int[] order = new int[0];

        Sub sub(int index) {
            for (Sub sub : subs) if (sub.index == index) return sub;
            return null;
        }

        Server server(int index) {
            for (Server server : servers) if (server.index == index) return server;
            return null;
        }

        Server selected() {
            for (Server server : servers) if (server.selected) return server;
            return null;
        }
    }

    static final class Rule {
        int index;
        String action = "";
        String type = "";
        String value = "";
        long hits;
    }

    static final class RoutingProfile {
        int index;
        boolean active;
        boolean globalProxy;
        int rules;
        int unsupported;
        int geoTags;
        long updated;
        String name = "";
    }

    static final class RoutingEntry {
        String action = "";
        boolean isIp;
        boolean supported;
        String entry = "";
    }

    static final class GeoState {
        boolean busy;
        long stamp;
        int missing;
        /** 0 none yet, 1 the last download finished, 2 it failed */
        int result;
        String message = "";
    }

    static final class DiagFact {
        String key = "";
        String value = "";
    }

    static final class CheckStage {
        String name = "";
        int ms;
        boolean ok;
    }

    static final class CheckResult {
        final List<CheckStage> stages = new ArrayList<CheckStage>();
        /** -1 when the check failed */
        int ms = -1;
        String error = "";
    }
}
