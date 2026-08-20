package com.termux.app.terminal.io;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;

public class TerminalToolbarViewPager {

    public static class PageAdapter extends PagerAdapter {

        final TermuxActivity mActivity;
        String mSavedTextInput;
        final boolean mExtraKeysOnly;

        public PageAdapter(TermuxActivity activity, String savedTextInput, boolean extraKeysOnly) {
            this.mActivity = activity;
            this.mSavedTextInput = savedTextInput;
            this.mExtraKeysOnly = extraKeysOnly;
        }

        @Override
        public int getCount() {
            return mExtraKeysOnly ? 1 : 2;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            LayoutInflater inflater = LayoutInflater.from(mActivity);
            View layout;
            if (position == 0) {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false);
                ExtraKeysView extraKeysView = (ExtraKeysView) layout;
                extraKeysView.setExtraKeysViewClient(mActivity.getTermuxTerminalExtraKeys());
                extraKeysView.setButtonTextAllCaps(mActivity.getProperties().shouldExtraKeysTextBeAllCaps());
                mActivity.setExtraKeysView(extraKeysView);
                extraKeysView.reload(mActivity.getTermuxTerminalExtraKeys().getExtraKeysInfo(),
                    mActivity.getTerminalToolbarDefaultHeight());

                // apply extra keys fix if enabled in prefs
                if (mActivity.getProperties().isUsingFullScreen() && mActivity.getProperties().isUsingFullScreenWorkAround()) {
                    FullScreenWorkAround.apply(mActivity);
                }

            } else {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false);
                final EditText editText = layout.findViewById(R.id.terminal_toolbar_text_input);
                setupTextInput(mActivity, editText, mSavedTextInput);
                mSavedTextInput = null;
            }
            collection.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object view) {
            collection.removeView((View) view);
        }

    }

    /** Configure either the legacy pager field or the opt-in field stacked above the extra keys. */
    public static void setupTextInput(TermuxActivity activity, EditText editText, String savedTextInput) {
        if (editText == null) return;
        if (savedTextInput != null) editText.setText(savedTextInput);

        editText.setOnEditorActionListener((v, actionId, event) -> {
            TerminalSession session = activity.getCurrentSession();
            if (session != null) {
                if (session.isRunning()) {
                    String textToSend = editText.getText().toString();
                    if (textToSend.length() == 0) textToSend = "\r";
                    session.write(textToSend);
                } else {
                    activity.getTermuxTerminalSessionClient().removeFinishedSession(session);
                }
                editText.setText("");
            }
            return true;
        });
    }



    public static class OnPageChangeListener extends ViewPager.SimpleOnPageChangeListener {

        final TermuxActivity mActivity;
        final ViewPager mTerminalToolbarViewPager;

        public OnPageChangeListener(TermuxActivity activity, ViewPager viewPager) {
            this.mActivity = activity;
            this.mTerminalToolbarViewPager = viewPager;
        }

        @Override
        public void onPageSelected(int position) {
            if (position == 0) {
                mActivity.getTerminalView().requestFocus();
            } else {
                final EditText editText = mTerminalToolbarViewPager.findViewById(R.id.terminal_toolbar_text_input);
                if (editText != null) editText.requestFocus();
            }
        }

    }

}
