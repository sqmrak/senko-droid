package com.senko.droid;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * the routing profile a subscription delivered, the geo lists it names, and
 * the user's own rules. a profile change applies at the next connect
 */
public final class RoutingActivity extends BaseActivity {
    private static final int GEO_POLL_MS = 2000;

    private Rows rows;
    private ControlClient.RoutingList profiles;
    private List<Model.Rule> rules;
    private Model.GeoState geo;
    private String notice = "";
    private boolean profilesLoaded;
    private final Runnable geoTick = new Runnable() {
        @Override
        public void run() {
            loadGeo();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("Routing");
        rows = new Rows(this);
        setBody(rows.view());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        reload();
    }

    @Override
    protected void onStop() {
        senko.handler().removeCallbacks(geoTick);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rows != null) render();
    }

    private void reload() {
        senko.run(false, new Senko.Work<ControlClient.RoutingList>() {
            @Override
            public ControlClient.RoutingList run(ControlClient client) throws IOException {
                return client.routing();
            }
        }, new Senko.Done<ControlClient.RoutingList>() {
            @Override
            public void done(ControlClient.RoutingList value, IOException error) {
                profiles = value;
                profilesLoaded = value != null;
                render();
                loadGeo();
            }
        });
        senko.run(false, new Senko.Work<List<Model.Rule>>() {
            @Override
            public List<Model.Rule> run(ControlClient client) throws IOException {
                return client.rules();
            }
        }, new Senko.Done<List<Model.Rule>>() {
            @Override
            public void done(List<Model.Rule> value, IOException error) {
                rules = value;
                render();
            }
        });
    }

    private void loadGeo() {
        senko.run(false, new Senko.Work<Model.GeoState>() {
            @Override
            public Model.GeoState run(ControlClient client) throws IOException {
                return client.geo();
            }
        }, new Senko.Done<Model.GeoState>() {
            @Override
            public void done(Model.GeoState value, IOException error) {
                geo = value;
                render();
                // a running download is polled until it ends, and not once it has
                senko.handler().removeCallbacks(geoTick);
                if (geo != null && geo.busy) senko.handler().postDelayed(geoTick, GEO_POLL_MS);
            }
        });
    }

    private Model.RoutingProfile active() {
        if (profiles == null) return null;
        for (Model.RoutingProfile profile : profiles.profiles) if (profile.index == profiles.active) return profile;
        return null;
    }

    private int appRules() {
        return Prefs.appDirect().size();
    }

    private void render() {
        rows.clear();
        final Model.RoutingProfile current = active();
        int active = profiles == null ? -1 : profiles.active;

        rows.section("Routing profile");
        rows.action("shield", "Off", active < 0 ? "✓" : "", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                choose(-1);
            }
        });
        if (profiles != null) {
            for (final Model.RoutingProfile profile : profiles.profiles) {
                Rows.Line line = rows.action("split", profile.name, profile.active ? "✓" : "", false,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                choose(profile.index);
                            }
                        });
                line.title.setText(profile.name + "\n" + detail(profile));
            }
        }
        rows.footer(footerProfile());

        if (current != null && current.geoTags > 0) {
            rows.section("Geo data");
            rows.action("cloud", "Update geo data", geoStatus(), geo == null || !geo.busy,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            if (geo == null || !geo.busy) updateGeo();
                        }
                    });
            rows.footer(Tr.t("The profile names geosite and geoip lists. They are downloaded once and kept for a week."));
        }

        rows.section("Rules");
        if (Build.VERSION.SDK_INT >= 21) {
            rows.action("hash", "Apps", appRules() > 0 ? Tr.f("%d with a rule", appRules()) : Tr.t("Choose how each app is routed"), open(AppRoutingActivity.class));
        }
        int others = rules == null ? 0 : rules.size();
        rows.action("globe", "Domains and addresses", others > 0 ? Tr.f("%d rules", others) : Tr.t("No rules yet"),
                open(RulesActivity.class));
        if (current != null) {
            rows.action("logs", "Profile entries", current.name, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(RoutingActivity.this, ProfileEntriesActivity.class);
                    intent.putExtra(ProfileEntriesActivity.EXTRA_INDEX, current.index);
                    intent.putExtra(ProfileEntriesActivity.EXTRA_NAME, current.name);
                    startActivity(intent);
                }
            });
        }
        rows.footer(Tr.t("A rule for an app wins over every domain and address rule. Block wins over direct, and direct over the tunnel."));
    }

    private View.OnClickListener open(final Class<?> screen) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(RoutingActivity.this, screen));
            }
        };
    }

    private String detail(Model.RoutingProfile profile) {
        StringBuilder text = new StringBuilder(Tr.f("%d rules", profile.rules));
        if (profile.unsupported > 0) text.append(" (").append(Tr.f("%d not supported", profile.unsupported)).append(")");
        text.append(" · ").append(Tr.t(profile.globalProxy ? "rest through the tunnel" : "rest direct"));
        return text.toString();
    }

    private String footerProfile() {
        if (!profilesLoaded) return Tr.t("Engine is unreachable");
        StringBuilder text = new StringBuilder();
        if (!notice.isEmpty()) text.append(notice).append('\n');
        text.append(Tr.t(profiles.profiles.isEmpty()
                ? "No subscription has sent a routing profile. A happ panel sends one in its response, and it shows up here after the next refresh."
                : "Profiles come from your subscriptions. A change applies the next time the tunnel connects."));
        return text.toString();
    }

    private String geoStatus() {
        if (geo == null) return Tr.t("unreachable");
        if (geo.busy) return Tr.t("Downloading...");
        if (geo.result == 2) return Tr.f("Failed: %s", geo.message);
        if (geo.missing > 0) return Tr.f("%d lists missing", geo.missing);
        if (geo.stamp == 0) return Tr.t("Not downloaded");
        return Tr.f("Updated %s", DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(geo.stamp * 1000L)));
    }

    private void choose(final int index) {
        if (profiles != null && index == profiles.active) return;
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.setRouting(index);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String reply, IOException error) {
                notice = error != null ? Senko.describe(error) : reply == null ? "" : reply;
                reload();
            }
        });
    }

    private void updateGeo() {
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.updateGeo();
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String reply, IOException error) {
                notice = error != null ? Senko.describe(error) : "";
                loadGeo();
            }
        });
    }
}
