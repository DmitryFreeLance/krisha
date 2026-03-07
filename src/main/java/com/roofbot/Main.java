package com.roofbot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws Exception {
        BotConfig config = BotConfig.fromEnv();
        Db db = new Db(config.dbPath());
        db.init();

        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        try {
            api.registerBot(new RoofBot(config, db));
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to register bot", e);
        }
    }
}
