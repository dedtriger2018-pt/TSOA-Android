#!/usr/bin/env python3
"""Check native-menu resources and production locale/preferences isolation.

Requires Python 3 and JDK 17 or newer. Android context/configuration interfaces
are doubled; TsoaOptions and TsoaMenuLanguage compile from their actual sources.
"""

import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET


STUBS = {
    "android/content/SharedPreferences.java": """
package android.content;
public interface SharedPreferences {
    String getString(String key, String fallback);
    boolean getBoolean(String key, boolean fallback);
    int getInt(String key, int fallback);
    float getFloat(String key, float fallback);
}
""",
    "android/os/LocaleList.java": """
package android.os;
import java.util.Locale;
public class LocaleList {
    public final Locale locale;
    public LocaleList(Locale... locales) { locale = locales[0]; }
}
""",
    "android/content/res/Configuration.java": """
package android.content.res;
import java.util.Locale;
import android.os.LocaleList;
public class Configuration {
    public Locale locale = Locale.JAPANESE;
    public Locale layoutLocale = Locale.JAPANESE;
    public Configuration() {}
    public Configuration(Configuration other) {
        locale = other.locale;
        layoutLocale = other.layoutLocale;
    }
    public void setLocales(LocaleList locales) { locale = locales.locale; }
    public void setLayoutDirection(Locale locale) { layoutLocale = locale; }
}
""",
    "android/content/res/Resources.java": """
package android.content.res;
public class Resources {
    private final Configuration configuration;
    public Resources(Configuration configuration) { this.configuration = configuration; }
    public Configuration getConfiguration() { return configuration; }
}
""",
    "android/content/Context.java": """
package android.content;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.HashMap;
import java.util.Map;
public class Context {
    public static final int MODE_PRIVATE = 0;
    public final Map<String, Object> values;
    private final Resources resources;
    public Context() { this(new HashMap<String, Object>(), new Configuration()); }
    private Context(Map<String, Object> values, Configuration configuration) {
        this.values = values;
        resources = new Resources(configuration);
    }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!name.equals("naninovel_launcher")) throw new AssertionError(name);
        return new SharedPreferences() {
            public String getString(String key, String fallback) {
                return values.containsKey(key) ? (String)values.get(key) : fallback;
            }
            public boolean getBoolean(String key, boolean fallback) {
                return values.containsKey(key) ? (Boolean)values.get(key) : fallback;
            }
            public int getInt(String key, int fallback) {
                return values.containsKey(key) ? (Integer)values.get(key) : fallback;
            }
            public float getFloat(String key, float fallback) {
                return values.containsKey(key) ? (Float)values.get(key) : fallback;
            }
        };
    }
    public Resources getResources() { return resources; }
    public Context createConfigurationContext(Configuration configuration) {
        return new Context(values, new Configuration(configuration));
    }
    public String getString(int resource, Object... arguments) {
        return String.format(resources.getConfiguration().locale, "%s", arguments);
    }
}
""",
}


HARNESS = """
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import android.content.Context;
import android.content.res.Configuration;
import com.winlator.tsoa.TsoaMenuLanguage;
import com.winlator.tsoa.TsoaOptions;

public class TsoaMenuLanguageCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Context base = new Context();
        Locale processLocale = Locale.getDefault();
        Configuration original = base.getResources().getConfiguration();
        TsoaOptions options = new TsoaOptions(base);
        require(options.getMenuLanguage().equals("pt"), "New installations must default to Portuguese");
        require(!options.isGameBloomEnabled(), "New installations must default Bloom Off");
        base.values.put("nativeLocale", "ja_JP.UTF-8");
        base.values.put("locale", "ja");
        base.values.put("wine_locale", "ja_JP");
        base.values.put("game_hdr_mode", 2);
        base.values.put("game_bloom_enabled", true);

        for (String saved : new String[] {"pt", "en", "zh-CN", "invalid", null}) {
            base.values.put("menu_language", saved);
            String expected = "en".equals(saved) ? "en" : "zh-CN".equals(saved) ? "zh-CN" : "pt";
            Map<String, Object> preferencesBefore = new HashMap<>(base.values);
            require(new TsoaOptions(base).getMenuLanguage().equals(expected), "Saved language fallback: " + saved);
            Context scoped = TsoaMenuLanguage.context(base);
            Configuration translated = scoped.getResources().getConfiguration();
            require(scoped != base && translated != original, "Menu must have a separate configuration context");
            require(translated.locale.toLanguageTag().equals(expected.equals("pt") ? "pt-BR" : expected), "Wrong resource locale: " + saved);
            require(translated.layoutLocale.toLanguageTag().equals(expected.equals("pt") ? "pt-BR" : expected), "Wrong scoped layout locale: " + saved);
            require(original.locale.equals(Locale.JAPANESE) && original.layoutLocale.equals(Locale.JAPANESE),
                    "Menu language changed the base configuration");
            require(Locale.getDefault().equals(processLocale), "Menu language changed the process locale");
            require(base.values.equals(preferencesBefore), "Reading menu language changed saved game/native settings");
            require(options.isGameBloomEnabled() && options.getGameHdrMode() == 2,
                    "Menu language changed game rendering options");
        }
        require(TsoaMenuLanguage.context(base, "unknown").getResources().getConfiguration().locale.getLanguage().equals("pt"),
                "Explicit invalid language must fall back to Portuguese");
        System.out.println("PASS: PT/Bloom defaults, saved menu language codes/fallbacks, cloned configuration, unchanged base/process/native/game settings");
    }
}
"""


FORMAT = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([tT])?([a-zA-Z%])")


def format_signature(value):
    signature = []
    implicit = 0
    for match in FORMAT.finditer(value):
        position, date_prefix, conversion = match.groups()
        if conversion in ("%", "n"):
            continue
        if position is None:
            implicit += 1
            position = str(implicit)
        signature.append((int(position), (date_prefix or "") + conversion.lower()))
    return sorted(signature)


def check_resources(repo):
    locales = ["values", "values-pt", "values-en", "values-zh-rCN"]
    strings = {}
    for locale in locales:
        path = repo / "app/src/main/res" / locale / "tsoa_strings.xml"
        items = [item for item in ET.parse(path).getroot() if item.tag == "string"]
        values = {item.attrib["name"]: "".join(item.itertext()) for item in items}
        assert values and len(values) == len(items), f"Empty file or duplicate keys: {path}"
        assert all(value.strip() for value in values.values()), f"Empty translation: {path}"
        strings[locale] = values
    reference = strings["values"]
    for locale, values in strings.items():
        assert values.keys() == reference.keys(), f"Missing or extra native-menu keys: {locale}"
        for name, value in values.items():
            assert format_signature(value) == format_signature(reference[name]), f"Format arguments differ: {locale}/{name}"
    assert strings["values-pt"] == reference, "Default resources must match explicit Portuguese"
    arrays = {"tsoa_editor_types": 7, "tsoa_editor_shapes": 4, "tsoa_editor_ranges": 4}
    for locale in locales:
        path = repo / "app/src/main/res" / locale / "tsoa_editor.xml"
        resource = ET.parse(path).getroot()
        items = {item.attrib["name"]: len(item.findall("item")) for item in resource if item.tag == "string-array"}
        assert {name: items.get(name) for name in arrays} == arrays, f"Control editor choices: {locale}"
    print(f"PASS: {len(reference)} native-menu keys across PT default/PT/EN/zh-CN, matching formats and complete translations")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    check_resources(repo)
    production = repo / "app/src/main/java/com/winlator/tsoa"
    with tempfile.TemporaryDirectory(prefix="tsoa-menu-language-test-") as directory:
        temporary = Path(directory)
        sources = temporary / "src"
        classes = temporary / "classes"
        for relative, text in STUBS.items():
            target = sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        (sources / "TsoaMenuLanguageCheck.java").write_text(HARNESS, encoding="utf-8")
        classes.mkdir()
        subprocess.run([
            args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main",
            "-source", "8", "-target", "8", "-Xlint:-options", "-d", str(classes),
            *map(str, sorted(sources.rglob("*.java"))),
            str(production / "TsoaOptions.java"), str(production / "TsoaMenuLanguage.java"),
        ], check=True)
        subprocess.run([args.java, "-cp", str(classes), "TsoaMenuLanguageCheck"], check=True)


if __name__ == "__main__":
    main()
