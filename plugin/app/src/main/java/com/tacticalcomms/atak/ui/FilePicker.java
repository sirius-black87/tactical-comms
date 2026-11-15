package com.tacticalcomms.atak.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

import static com.tacticalcomms.atak.ui.Views.*;

/** Minimal file browser (ATAK plugins cannot easily use startActivityForResult). */
final class FilePicker {
    interface Callback {
        void onFile(File f);
    }

    private FilePicker() {}

    static void show(Context ctx, Callback cb) {
        File start = FileSystemUtils.getRoot();
        if (start == null || !start.exists()) start = Environment.getExternalStorageDirectory();
        new Browser(ctx, cb).open(start);
    }

    private static final class Browser {
        final Context ctx;
        final Callback cb;
        final LinearLayout list;
        final TextView path;
        final AlertDialog dialog;

        Browser(Context ctx, Callback cb) {
            this.ctx = ctx;
            this.cb = cb;
            LinearLayout root = column(ctx);
            int p = dp(ctx, 12);
            root.setPadding(p, p / 2, p, 0);
            path = new TextView(ctx);
            root.addView(path);
            ScrollView sv = new ScrollView(ctx);
            list = column(ctx);
            sv.addView(list);
            root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 360)));
            dialog = new AlertDialog.Builder(Ui.mapContext())
                    .setTitle("Choose a file to send")
                    .setView(root)
                    .setNegativeButton("Cancel", null)
                    .create();
        }

        void open(File dir) {
            path.setText(dir.getAbsolutePath());
            list.removeAllViews();
            final File parent = dir.getParentFile();
            if (parent != null && parent.canRead()) addEntry("⬆ ..", parent, true);
            File[] entries = dir.listFiles();
            if (entries == null) entries = new File[0];
            Arrays.sort(entries, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : entries) {
                if (f.getName().startsWith(".")) continue;
                addEntry(f.isDirectory() ? "📁 " + f.getName() : "📄 " + f.getName() + "  (" + Ui.formatSize(f.length()) + ")",
                        f, f.isDirectory());
            }
            if (!dialog.isShowing()) dialog.show();
        }

        void addEntry(String label, final File f, final boolean isDir) {
            TextView t = new TextView(ctx);
            t.setText(label);
            t.setTextSize(15);
            int p = dp(ctx, 10);
            t.setPadding(0, p, 0, p);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (isDir) {
                        open(f);
                    } else {
                        dialog.dismiss();
                        cb.onFile(f);
                    }
                }
            });
            list.addView(t);
        }
    }
}
