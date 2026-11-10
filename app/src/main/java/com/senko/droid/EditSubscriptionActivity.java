package com.senko.droid;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;

/** name, address and the extra request header of one subscription */
public final class EditSubscriptionActivity extends BaseActivity {
    static final String EXTRA_INDEX = "index";

    private int index;
    private EditText name;
    private EditText url;
    private EditText header;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("Edit details");
        index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        Model.Sub sub = senko.catalog().sub(index);
        if (sub == null) {
            finish();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        name = field(form, "Name", sub.name.replace('_', ' '), InputType.TYPE_CLASS_TEXT);
        url = field(form, "Address", sub.url, Dialogs.urlInput());
        header = field(form, "Request header", sub.header, Dialogs.plainInput());
        TextView hint = Ui.text(this, Tr.t("Sent with every refresh, written as Name: value"), 12,
                Theme.color(Theme.INK_MUTED), false);
        hint.setPadding(Ui.dp(4), Ui.dp(4), 0, 0);
        form.addView(hint);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        setBody(scroll);
        addBarText("Save", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });
    }

    private EditText field(LinearLayout form, String label, String value, int type) {
        TextView caption = Ui.text(this, Tr.t(label), 13, Theme.color(Theme.INK_MUTED), false);
        caption.setPadding(Ui.dp(4), Ui.dp(10), 0, Ui.dp(4));
        form.addView(caption);
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(type | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setText(value);
        edit.setTextColor(Theme.ink());
        edit.setTextSize(15);
        edit.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        Ui.background(edit, Ui.plate(10, Theme.color(Theme.WELL)));
        form.addView(edit, new LinearLayout.LayoutParams(-1, Ui.dp(42)));
        return edit;
    }

    private void save() {
        hideKeyboard();
        final String newName = name.getText().toString().trim();
        final String newUrl = url.getText().toString().trim();
        final String newHeader = header.getText().toString().trim();
        if (newUrl.isEmpty()) {
            toast("Address is required");
            return;
        }
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.replaceSubscription(index, newName.isEmpty() ? Format.subscriptionName(newUrl) : newName,
                        newUrl, newHeader);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                senko.loadCatalog(null);
                if (error != null) toastError(error);
                else finish();
            }
        });
    }
}
