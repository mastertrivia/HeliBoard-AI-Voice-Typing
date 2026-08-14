// Ported from SpeechNotes' c.c.b.b. Logic identical.
package helium314.keyboard.voice;

import java.util.Arrays;

/** Trim helpers (live) + language-specific punctuation dictionary (unused in this build, kept for fidelity). */
public class TextTrim {
    public TextTrim(String str, Boolean bool) {
        char c2;
        String str2 = str.split("-", -1)[0];
        switch (str2.hashCode()) {
            case 3121:
                if (str2.equals("ar")) {
                    c2 = 7;
                    break;
                }
                c2 = 65535;
                break;
            case 3197:
                if (str2.equals("da")) {
                    c2 = '\n';
                    break;
                }
                c2 = 65535;
                break;
            case 3201:
                if (str2.equals("de")) {
                    c2 = 0;
                    break;
                }
                c2 = 65535;
                break;
            case 3246:
                if (str2.equals("es")) {
                    c2 = 1;
                    break;
                }
                c2 = 65535;
                break;
            case 3276:
                if (str2.equals("fr")) {
                    c2 = 2;
                    break;
                }
                c2 = 65535;
                break;
            case 3371:
                if (str2.equals("it")) {
                    c2 = 3;
                    break;
                }
                c2 = 65535;
                break;
            case 3383:
                if (str2.equals("ja")) {
                    c2 = 5;
                    break;
                }
                c2 = 65535;
                break;
            case 3518:
                if (str2.equals("nl")) {
                    c2 = '\t';
                    break;
                }
                c2 = 65535;
                break;
            case 3588:
                if (str2.equals("pt")) {
                    c2 = '\b';
                    break;
                }
                c2 = 65535;
                break;
            case 3651:
                if (str2.equals("ru")) {
                    c2 = 4;
                    break;
                }
                c2 = 65535;
                break;
            case 98628:
                if (str2.equals("cmn")) {
                    c2 = 6;
                    break;
                }
                c2 = 65535;
                break;
            default:
                c2 = 65535;
                break;
        }
        switch (c2) {
            case 0:
                Arrays.asList("punkt", "komma", "fragezeichen", "doppelpunkt", "semikolon", "semikolon", "semikolon", "ausrufezeichen", "ausrufezeichen", "neue zeile", "neuer absatz", "klammer öffnen", "klammer schließen", "bindestrich", "smiley", "trauriges gesicht", "Bindestrich");
                Arrays.asList(".", ",", "?", ":", ";", ";", ";", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case 1:
                Arrays.asList("coma", "signo de interrogación", "dos puntos", "2 puntos", "punto y coma", "punto y,", "punto y ,", ". y coma", ". y,", ". y ,", "punto", "signo de exclamación", "exclamación", "nueva línea", "nuevo apartado", "abrir paréntesis", "cerrar paréntesis", "guión", "cara sonriente", "cara triste", "guión");
                Arrays.asList(",", "?", ":", ":", ";", ";", ";", ";", ";", ";", ".", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case 2:
                Arrays.asList("virgule", "point d'interrogation", "deux-points", "deux points", "2 points", "point-virgule", "point virgule", "point ,", "point,", "point d'exclamation", "point", "nouvelle ligne", "nouveau paragraphe", "ouvrir la parenthèse", "fermer la parenthèse", "tiret", "smiley", "visage triste", "tiret");
                Arrays.asList(",", "?", ":", ":", ":", ";", ";", ";", ";", "!", ".", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case 3:
                Arrays.asList("virgula", "punto interrogativo", "due punti", "2 punti", "punto e virgola", "punto e,", "punto e ,", "esclamativo", "punto esclamativo", "punto", "nuova riga", "nuovo paragrafo", "apri parentesi", "chiudi parentesi", "trattino", "smiley", "faccina sorridente", "faccina triste", "trattino");
                Arrays.asList(",", "?", ":", ":", ";", ";", ";", "!", "!", ".", "\n", "\n\n", "(", ")", "-", ":-)", ":-)", ":-(", "-");
                break;
            case 4:
                Arrays.asList("запятая", "вопросительный знак", "двоеточие", "точка с запятой", "точка с,", "точка с ,", "точка", "восклицательный символ", "восклицательный знак", "новая строка", "новый параграф", "открывающаяся скобка", "закрывающаяся скобка", "тире", "смайлик", "улыбочка", "грустное лицо", "тире");
                Arrays.asList(",", "?", ":", ";", ";", ";", ".", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-)", ":-(", "-");
                break;
            case 5:
                Arrays.asList("ピリオド", "コンマ", "疑問符", "コロン", "セミコロン", "感嘆符", "感嘆符記号", "改行", "新しい段落", "括弧開き", "括弧閉じ", "ダッシュ", "スマイリー", "悲しい顔", "ダッシュ");
                Arrays.asList(".", ",", "?", ":", ";", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case 6:
                Arrays.asList("句号", "逗号", "问号", "冒号", "分号", "感叹号", "换行", "新段落", "左圆括号", "右圆括号", "破折号", "笑脸", "悲伤的脸", "破折号");
                Arrays.asList(".", ",", "?", ":", ";", "!", "\n", "\n\n", "(", ")", "——", ":-)", ":-(", "——");
                break;
            case 7:
                Arrays.asList("فترة", "فاصلة مفاصلة", "علامة استفهام", "نقطتان", "نقوطة", "طة التعجب", "علامة تعجب،", "خط جديد", "فقرة جديدة", "افتح القوسان", "أغلق القوسان", "الشرطة", "مبتسم", "وجه حزين", "الشرطة");
                Arrays.asList(".", ";", "?", ":", ",", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case '\b':
                Arrays.asList("interrogação", "dois pontos", "2 pontos", "ponto e vírgula", "ponto e,", "ponto e ,", "ponto", "vírgula", "exclamação", "nova linha", "parágrafo", "abre parêntese", "fecha parêntese", "hífen", "smiley", "rosto triste", "hífen");
                Arrays.asList("?", ":", ":", ";", ";", ";", ".", ",", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case '\t':
                Arrays.asList("punt", "komma", "vraagteken", "uitroepteken");
                Arrays.asList(".", ",", "?", "!");
                break;
            case '\n':
                Arrays.asList("punktum", "komma", "spørgsmålstegn", "udråbstegn", "tankestreg", "kolon", "ny linie", "nyt afsnit", "venstre parantes", "højre parantes");
                Arrays.asList(".", ",", "?", "!", "-", ":", "\n", "\n\n", "(", ")");
                break;
            default:
                Arrays.asList("period", "comma", "question mark", "colon", "semicolon", "semi colon", "semi:", "semi :", "exclamation mark", "exclamation point", "new line", "new paragraph", "open parenthesis", "open parentheses", "close parenthesis", "close parentheses", "hyphen", "smiley", "smiley face", "sad face", "dash", "open quotation", "close quotation", "quotation");
                Arrays.asList(".", ",", "?", ":", ";", ";", ";", ";", "!", "!", "\n", "\n\n", "(", "(", ")", ")", "-", ":-)", ":-)", ":-(", "-", "“", "”", "\"");
                break;
        }
    }

    /** Trim trailing spaces. (was a(String)) */
    public static String trimEnd(String str) {
        if (str.length() <= 0) {
            return str;
        }
        while (str.substring(str.length() - 1).equals(" ") && str.length() > 0) {
            str = str.substring(0, str.length() - 1);
            if (str.length() == 0) {
                return "";
            }
        }
        return str;
    }

    /** Trim leading spaces. (was b(String)) */
    public static String trimStart(String str) {
        if (str.length() > 0) {
            while (str.substring(0).equals(" ") && str.length() > 0) {
                str = str.substring(1);
            }
        }
        return str;
    }
}
