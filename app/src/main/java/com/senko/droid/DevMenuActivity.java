package com.senko.droid;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/** developer tools, switched on from About. each row opens one tool */
public final class DevMenuActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("Developer settings");
        Rows rows = new Rows(this);
        setBody(rows.view());
        rows.section(null);
        row(rows, "chart", "State", "Backend, tunnel, ports, DNS, rules", DevToolsActivity.STATE);
        row(rows, "search", "Checks", "Staged probes of one server", DevToolsActivity.CHECKS);
        row(rows, "gear", "Force", "Listeners, trace, DNS answers", DevToolsActivity.FORCE);
        row(rows, "shield", "Rescue", "Device id, rules, debug bundle", DevToolsActivity.RESCUE);
        row(rows, "code", "Console", "Talk to the control socket", DevToolsActivity.CONSOLE);
    }

    private void row(Rows rows, String glyph, String title, String detail, final String tool) {
        Rows.Line line = rows.action(glyph, title, "", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(DevMenuActivity.this, DevToolsActivity.class);
                intent.putExtra(DevToolsActivity.EXTRA_TOOL, tool);
                startActivity(intent);
            }
        });
        line.title.setText(Tr.t(title) + "\n" + Tr.t(detail));
    }
}
