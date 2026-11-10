package com.senko.droid;

import android.os.Bundle;

import java.io.IOException;

/** the entries of one routing profile, so a user can see what a panel's profile really asks for */
public final class ProfileEntriesActivity extends BaseActivity {
    static final String EXTRA_INDEX = "index";
    static final String EXTRA_NAME = "name";

    private Rows rows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        setScreenTitle("Profile entries");
        rows = new Rows(this);
        setBody(rows.view());
        rows.section(name == null ? null : name);
        load(getIntent().getIntExtra(EXTRA_INDEX, -1));
    }

    private void load(final int index) {
        senko.run(false, new Senko.Work<ControlClient.RoutingEntries>() {
            @Override
            public ControlClient.RoutingEntries run(ControlClient client) throws IOException {
                return client.routingEntries(index);
            }
        }, new Senko.Done<ControlClient.RoutingEntries>() {
            @Override
            public void done(ControlClient.RoutingEntries value, IOException error) {
                rows.clear();
                rows.section(null);
                if (error != null || value == null) {
                    rows.footer(error == null ? Tr.t("Engine is unreachable") : Senko.describe(error));
                    return;
                }
                for (Model.RoutingEntry entry : value.entries) {
                    Rows.Line line = rows.action(entry.isIp ? "hash" : "globe", entry.entry,
                            RulesActivity.actionName(entry.action), false, null);
                    ViewFx.alpha(line.view, entry.supported ? 1f : 0.5f);
                }
                if (value.total > value.entries.size())
                    rows.footer(Tr.f("Showing %d of %d", value.entries.size(), value.total));
            }
        });
    }
}
