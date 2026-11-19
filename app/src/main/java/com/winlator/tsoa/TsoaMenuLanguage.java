package com.winlator.tsoa;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/** Resources scoped to the native launcher menu, without changing the game or process locale. */
public final class TsoaMenuLanguage {
    private TsoaMenuLanguage() {}

    public static Context context(Context base) {
        return context(base, new TsoaOptions(base).getMenuLanguage());
    }

    public static Context context(Context base, String language) {
        Locale locale = "en".equals(language) ? Locale.ENGLISH
                : "zh-CN".equals(language) ? Locale.SIMPLIFIED_CHINESE : Locale.forLanguageTag("pt-BR");
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    public static String get(Context base, int resource, Object... args) {
        return context(base).getString(resource, args);
    }
}
