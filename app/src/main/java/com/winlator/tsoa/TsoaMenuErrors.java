package com.winlator.tsoa;

import android.content.Context;

import com.winlator.R;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Translates known launcher errors at the UI boundary without changing runtime or archive code. */
public final class TsoaMenuErrors {
    private static final String RESTORE_INTERRUPTED = "Restauração interrompida. Seus saves anteriores estão preservados em ";
    private static final String INVALID_INSTALLATION_NAME = "Nome de arquivo inválido na instalação: ";
    private static final String CREATE_IMPORT_FILE = "Não foi possível criar: ";
    private static final String READ_IMPORT_FILE = "Não foi possível ler: ";
    private static final Pattern INSUFFICIENT_SPACE = Pattern.compile(
        "Espaço insuficiente\\. A cópia exige aproximadamente ([0-9]+(?:\\.[0-9]+)?) GB e pelo menos " +
        "([0-9]+(?:\\.[0-9]+)?) GB livres foram reservados para segurança\\. Disponível: ([0-9]+(?:\\.[0-9]+)?) GB\\.");

    private TsoaMenuErrors() {}

    /** Unknown platform/provider errors retain their original detail; a missing message stays null. */
    public static String message(Context context, Throwable error) {
        if (error == null || error.getMessage() == null) return null;
        String message = error.getMessage();
        switch (message) {
            case "Ainda não há uma pasta de saves do TSOA neste aparelho.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_no_save_directory);
            case "Nenhum save ou configuração foi encontrado. Jogue e salve antes de exportar.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_no_saves_found);
            case "A pasta de saves contém subpastas demais.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_save_depth);
            case "Não foi possível ler a pasta de saves.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_read_save_directory);
            case "Caminho fora da pasta de saves.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_outside_save_directory);
            case "A pasta de saves contém arquivos demais.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_too_many_saves);
            case "Não foi possível criar a pasta de saves.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_create_save_directory);
            case "A pasta de saves não pode ser um link.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_save_directory_link);
            case "Não foi possível preparar a restauração.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_prepare_restore);
            case "Não foi possível preservar os saves atuais; nada foi alterado.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_preserve_current_saves);
            case "Não foi possível instalar os saves. A versão anterior foi mantida.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_install_saves);
            case "O backup contém entradas demais.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_too_many_backup_entries);
            case "Entrada repetida no backup.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_duplicate_backup_entry);
            case "Este arquivo não é um backup de saves do TSOA.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_not_tsoa_backup);
            case "O backup contém arquivos fora da pasta de saves.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_outside_backup_directory);
            case "Caminho inválido no backup.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_invalid_backup_path);
            case "Não foi possível criar uma subpasta.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_create_subdirectory);
            case "Um arquivo do backup excede o limite de 256 MB.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_backup_file_too_large);
            case "O arquivo não contém um backup válido de saves do TSOA.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_invalid_save_backup);
            case "O backup excede o limite de 256 MB descompactados.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_backup_too_large);
            case "Nome de arquivo inválido no backup.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_invalid_backup_name);
            case "O backup contém subpastas demais.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_backup_depth);
            case "Não foi possível criar a pasta temporária de importação.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_create_import_directory);
            case "O armazenamento livre está muito baixo para iniciar a importação com segurança.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_low_storage);
            case "A pasta selecionada não parece ser a instalação completa do TSOA. Selecione a pasta que contém TSOA.exe e TSOA_Data.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_incomplete_game_folder);
            case "Não foi possível preservar a instalação anterior.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_preserve_installation);
            case "Não foi possível finalizar a importação.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_finish_import);
            case "O provedor de arquivos não retornou o conteúdo da pasta.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_provider_empty);
            case "A importação foi interrompida para evitar que o armazenamento do celular fique sem espaço.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_import_storage_stop);
            case "Sem permissão para ler a pasta selecionada.": return TsoaMenuLanguage.get(context, R.string.tsoa_error_read_permission);
            default: break;
        }
        if (message.startsWith(RESTORE_INTERRUPTED)) {
            return TsoaMenuLanguage.get(context, R.string.tsoa_error_restore_interrupted, message.substring(RESTORE_INTERRUPTED.length()));
        }
        if (message.startsWith(INVALID_INSTALLATION_NAME)) {
            return TsoaMenuLanguage.get(context, R.string.tsoa_error_invalid_installation_name, message.substring(INVALID_INSTALLATION_NAME.length()));
        }
        if (message.startsWith(CREATE_IMPORT_FILE)) {
            return TsoaMenuLanguage.get(context, R.string.tsoa_error_create_import_file, message.substring(CREATE_IMPORT_FILE.length()));
        }
        if (message.startsWith(READ_IMPORT_FILE)) {
            return TsoaMenuLanguage.get(context, R.string.tsoa_error_read_import_file, message.substring(READ_IMPORT_FILE.length()));
        }
        Matcher space = INSUFFICIENT_SPACE.matcher(message);
        if (space.matches()) {
            return TsoaMenuLanguage.get(context, R.string.tsoa_error_insufficient_space,
                space.group(1), space.group(2), space.group(3));
        }
        return message;
    }
}
