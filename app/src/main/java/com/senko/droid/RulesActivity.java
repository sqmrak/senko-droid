package com.senko.droid;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** the user's own domain and address rules, with how often each one matched */
public final class RulesActivity extends BaseActivity {
    private static final String[] ACTIONS = { "proxy", "direct", "block" };
    private static final String[] TYPES = { "domain-suffix", "domain-keyword", "ip-cidr" };

    private Rows rows;
    private List<Model.Rule> rules;
    private String pendingAction = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("Routing rules");
        rows = new Rows(this);
        setBody(rows.view());
        addBarIcon(Icons.shape(this, Icons.Shape.PLUS, barInk()), "Add", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseAction();
            }
        });
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    static String actionName(String action) {
        if (action.equals("direct")) return Tr.t("Direct");
        if (action.equals("block")) return Tr.t("Block");
        return Tr.t("Through the tunnel");
    }

    static String typeName(String type) {
        if (type.equals("domain-keyword")) return Tr.t("Keyword");
        if (type.equals("ip-cidr")) return Tr.t("IP range");
        if (type.equals("domain-full")) return Tr.t("Exact domain");
        if (type.equals("app")) return Tr.t("App");
        if (type.equals("geosite")) return Tr.t("Geosite list");
        if (type.equals("geoip")) return Tr.t("Geoip list");
        return Tr.t("Domain and subdomains");
    }

    private void load() {
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

    private void render() {
        rows.clear();
        rows.section(null);
        if (rules != null) {
            for (final Model.Rule rule : rules) {
                Rows.Line line = rows.action(null, rule.value, "", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        confirmDelete(rule);
                    }
                });
                line.title.setText(rule.value + "\n" + actionName(rule.action) + " · " + typeName(rule.type)
                        + " · " + Tr.t("hits") + " " + rule.hits);
            }
        }
        rows.footer(Tr.t(rules == null ? "Reading the rules from the engine..."
                : rules.isEmpty() ? "No rules: everything goes through the tunnel. Add one with the plus button."
                : "Block wins over direct, direct wins over the tunnel, whatever the order. The tunnel core reads the real domain from the connection."));
    }

    private void chooseAction() {
        List<String> labels = new ArrayList<String>();
        for (String action : ACTIONS) labels.add(actionName(action));
        Dialogs.menu(this, "What should happen to the traffic?", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                pendingAction = ACTIONS[index];
                chooseType();
            }
        });
    }

    private void chooseType() {
        List<String> labels = new ArrayList<String>();
        for (String type : TYPES) labels.add(typeName(type));
        Dialogs.menu(this, "What should it match?", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                enterValue(TYPES[index]);
            }
        });
    }

    private void enterValue(final String type) {
        String hint = type.equals("ip-cidr") ? "For example 10.0.0.0/8"
                : type.equals("domain-keyword") ? "For example googlevideo" : "For example example.com";
        Dialogs.prompt(this, "Value", hint, "",
                type.equals("ip-cidr") ? InputType.TYPE_CLASS_PHONE : Dialogs.urlInput(), "Add",
                new Dialogs.Text() {
                    @Override
                    public void entered(final String value) {
                        final String action = pendingAction;
                        senko.run(false, new Senko.Work<String>() {
                            @Override
                            public String run(ControlClient client) throws IOException {
                                return client.addRule(action, type, value);
                            }
                        }, new Senko.Done<String>() {
                            @Override
                            public void done(String reply, IOException error) {
                                if (error != null) Dialogs.error(RulesActivity.this, Tr.t("Routing rules"),
                                        Senko.describe(error));
                                load();
                            }
                        });
                    }
                });
    }

    private void confirmDelete(final Model.Rule rule) {
        Dialogs.confirm(this, "Delete rule", "Delete this rule?", "Delete", new Runnable() {
            @Override
            public void run() {
                senko.run(false, new Senko.Work<String>() {
                    @Override
                    public String run(ControlClient client) throws IOException {
                        return client.deleteRule(rule.index);
                    }
                }, new Senko.Done<String>() {
                    @Override
                    public void done(String reply, IOException error) {
                        if (error != null) Dialogs.error(RulesActivity.this, Tr.t("Routing rules"),
                                Senko.describe(error));
                        load();
                    }
                });
            }
        });
    }
}
