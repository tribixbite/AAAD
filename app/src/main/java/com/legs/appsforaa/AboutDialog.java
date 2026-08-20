package com.legs.appsforaa;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;


public class AboutDialog extends DialogFragment {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());

        // TODO(standalone): the "did" argument was the backend device id used for license
        // support. There is no backend in this fork — replace this trailer with the app
        // version once MainActivityNew is reimplemented (T-04), and drop the argument.
        final String deviceId = getArguments().getString("did");

        builder.setMessage(Html.fromHtml(getString(R.string.about_part_one) + getString(R.string.about_part_three) + getString(R.string.about_part_two) + "\nDID " + deviceId));
        builder.setCancelable(true);
        builder.setPositiveButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });

        builder.setNegativeButton(getString(R.string.privacy), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                builder.setMessage(Html.fromHtml(getString(R.string.privacy_policy)));
                builder.setNegativeButton(null, null);
                AlertDialog Alert = builder.create();
                Alert.show();

            }
        });

        AlertDialog Alert = builder.create();
        Alert.show();
        ((TextView)Alert.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
        return Alert;
    }


}
