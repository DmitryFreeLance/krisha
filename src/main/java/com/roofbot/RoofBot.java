package com.roofbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendContact;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RoofBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(RoofBot.class);

    private static final String STEP_Q1 = "Q1";
    private static final String STEP_Q2 = "Q2";
    private static final String STEP_Q3 = "Q3";
    private static final String STEP_CONTACT = "CONTACT";
    private static final String STEP_NAME = "NAME";
    private static final String STEP_PHONE = "PHONE";
    private static final String STEP_EMAIL = "EMAIL";
    private static final String STEP_DONE = "DONE";
    private static final String START_PHOTO_CACHE_KEY = "start_photo";
    private static final String MENU_TEXT = "↩️ Вернуться в меню";

    private final BotConfig config;
    private final Db db;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public RoofBot(BotConfig config, Db db) {
        this.config = config;
        this.db = db;
        scheduler.scheduleAtFixedRate(this::checkAbandonedSessions, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public String getBotUsername() {
        return config.username();
    }

    @Override
    public String getBotToken() {
        return config.token();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to handle update", e);
        }
    }

    private void handleMessage(Message message) throws Exception {
        if (message.getFrom() == null) {
            return;
        }
        long userId = message.getFrom().getId();
        long chatId = message.getChatId();
        Session session = db.getSession(userId);
        UserProfile profile = new UserProfile(
                userId,
                message.getFrom().getUserName(),
                message.getFrom().getFirstName(),
                message.getFrom().getLastName()
        );
        if (session != null) {
            db.touchInteraction(userId, profile);
            if (session.abandoned()) {
                db.clearAbandoned(userId);
            }
        }

        if (message.hasText()) {
            String text = message.getText().trim();

            if ("/start".equalsIgnoreCase(text)) {
                sendStartMenu(chatId, userId);
                return;
            }

            if ("/admin".equalsIgnoreCase(text) && config.isAdmin(userId)) {
                sendAdminMenu(chatId);
                return;
            }

            if (session != null && session.step() != null) {
                switch (session.step()) {
                    case STEP_NAME -> {
                        if (session.contactMethod() == null || "Телефон".equals(session.contactMethod())) {
                            db.updateName(userId, text, STEP_PHONE);
                            askPhone(chatId);
                        } else if ("Почта".equals(session.contactMethod())) {
                            db.updateName(userId, text, STEP_EMAIL);
                            askEmail(chatId);
                        } else {
                            db.updateName(userId, text, STEP_DONE);
                            Session updated = db.getSession(userId);
                            if (updated != null) {
                                db.saveLead(profile, updated);
                                notifyAdminsNewLead(profile, updated);
                            }
                            sendThankYou(chatId);
                        }
                        return;
                    }
                    case STEP_PHONE -> {
                        db.updatePhone(userId, text);
                        Session updated = db.getSession(userId);
                        if (updated != null) {
                            db.saveLead(profile, updated);
                            notifyAdminsNewLead(profile, updated);
                        }
                        sendThankYou(chatId);
                        return;
                    }
                    case STEP_EMAIL -> {
                        db.updateEmail(userId, text);
                        Session updated = db.getSession(userId);
                        if (updated != null) {
                            db.saveLead(profile, updated);
                            notifyAdminsNewLead(profile, updated);
                        }
                        sendThankYou(chatId);
                        return;
                    }
                    default -> {
                        // ignore and show menu
                    }
                }
            }

            sendStartMenu(chatId, userId);
        }
    }

    private void handleCallback(CallbackQuery callback) throws Exception {
        long userId = callback.getFrom().getId();
        long chatId = callback.getMessage().getChatId();
        String data = callback.getData();

        Session session = db.getSession(userId);
        UserProfile profile = new UserProfile(
                userId,
                callback.getFrom().getUserName(),
                callback.getFrom().getFirstName(),
                callback.getFrom().getLastName()
        );
        if (session != null) {
            db.touchInteraction(userId, profile);
            if (session.abandoned()) {
                db.clearAbandoned(userId);
            }
        }

        answerCallback(callback.getId());

        if ("calc_start".equals(data)) {
            db.createOrResetSession(userId, STEP_Q1, profile);
            askQ1(chatId);
            return;
        }

        if (data.startsWith("q1_")) {
            String answer = q1Label(data);
            db.updateAnswer(userId, "q1", answer, STEP_Q2);
            askQ2(chatId);
            return;
        }

        if (data.startsWith("q2_")) {
            String answer = q2Label(data);
            db.updateAnswer(userId, "q2", answer, STEP_Q3);
            askQ3(chatId);
            return;
        }

        if (data.startsWith("q3_")) {
            String answer = q3Label(data);
            db.updateAnswer(userId, "q3", answer, STEP_CONTACT);
            askContactMethod(chatId);
            return;
        }

        if (data.startsWith("contact_")) {
            String method = contactLabel(data);
            db.updateAnswer(userId, "contact_method", method, STEP_NAME);
            askName(chatId);
            return;
        }

        if ("about".equals(data)) {
            sendAbout(chatId);
            return;
        }

        if ("contact".equals(data)) {
            sendContact(chatId);
            return;
        }

        if ("call_phone".equals(data)) {
            sendPhoneCard(chatId);
            return;
        }

        if ("menu".equals(data)) {
            Session current = db.getSession(userId);
            if (current != null && current.step() != null && !STEP_DONE.equals(current.step())) {
                db.markAbandoned(userId, stageName(current.step()));
            }
            sendStartMenu(chatId, userId);
            return;
        }

        if ("admin_menu".equals(data) && config.isAdmin(userId)) {
            sendAdminMenu(chatId);
            return;
        }

        if ("admin_last5".equals(data) && config.isAdmin(userId)) {
            sendAdminLastLeads(chatId);
            return;
        }

        if ("admin_stats".equals(data) && config.isAdmin(userId)) {
            sendAdminStats(chatId);
            return;
        }

        if ("admin_back".equals(data) && config.isAdmin(userId)) {
            sendStartMenu(chatId, userId);
        }
    }

    private void sendStartMenu(long chatId, long userId) throws TelegramApiException {
        clearReplyKeyboard(chatId);

        String text = "<b>СК КРОВЛЯ ПОД КЛЮЧ</b>\n" +
                "Кровельные работы любой сложности.\n\n" +
                "Наши крыши не текут, за нами не надо переделывать !";

        InlineKeyboardMarkup keyboard = mainMenuKeyboard(config.isAdmin(userId));

        try {
            String cachedFileId = db.getMediaFileId(START_PHOTO_CACHE_KEY);
            if (cachedFileId != null && !cachedFileId.isBlank()) {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(String.valueOf(chatId));
                photo.setPhoto(new InputFile(cachedFileId));
                photo.setCaption(text);
                photo.setParseMode(ParseMode.HTML);
                photo.setReplyMarkup(keyboard);
                execute(photo);
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to load cached media id", e);
        }

        if (Files.exists(config.photoPath())) {
            SendPhoto photo = new SendPhoto();
            photo.setChatId(String.valueOf(chatId));
            photo.setPhoto(new InputFile(config.photoPath().toFile()));
            photo.setCaption(text);
            photo.setParseMode(ParseMode.HTML);
            photo.setReplyMarkup(keyboard);
            Message sent = execute(photo);
            cacheStartPhotoId(sent);
        } else {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText(text + "\n\n(Фото 1.jpg не найдено на сервере)");
            msg.setParseMode(ParseMode.HTML);
            msg.setReplyMarkup(keyboard);
            execute(msg);
        }
    }

    private void clearReplyKeyboard(long chatId) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(" ");
        msg.setReplyMarkup(new ReplyKeyboardRemove(true));
        try {
            Message sent = execute(msg);
            DeleteMessage deleteMessage = new DeleteMessage(String.valueOf(chatId), sent.getMessageId());
            execute(deleteMessage);
        } catch (TelegramApiException e) {
            log.warn("Failed to remove reply keyboard", e);
        }
    }

    private InlineKeyboardMarkup mainMenuKeyboard(boolean isAdmin) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(callbackButton("🧮 Расчет кровельных работ", "calc_start")));
        rows.add(List.of(callbackButton("ℹ️ О нас", "about")));
        rows.add(List.of(callbackButton("📞 Связь с прорабом", "contact")));
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private void askQ1(long chatId) throws TelegramApiException {
        sendQuestion(chatId, "Что необходимо сделать?", q1Buttons());
    }

    private void askQ2(long chatId) throws TelegramApiException {
        sendQuestion(chatId, "Выберите тип кровли:", q2Buttons());
    }

    private void askQ3(long chatId) throws TelegramApiException {
        sendQuestion(chatId, "Какая примерно площадь кровли?", q3Buttons());
    }

    private void askContactMethod(long chatId) throws TelegramApiException {
        sendQuestion(chatId, "Выберите предпочтительный способ связи:", contactButtons());
    }

    private void askName(long chatId) throws TelegramApiException {
        sendQuestion(chatId, "Введите ваше имя:", menuOnlyButtons());
    }

    private void askPhone(long chatId) throws TelegramApiException {
        sendQuestion(chatId, "Введите телефон для связи:", menuOnlyButtons());
    }

    private void askEmail(long chatId) throws TelegramApiException {
        sendQuestion(chatId, "Введите e-mail для связи:", menuOnlyButtons());
    }

    private void sendThankYou(long chatId) throws TelegramApiException {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("Спасибо! Мы скоро свяжемся с вами.\nВы можете вернуться в меню.");
        msg.setReplyMarkup(menuOnlyButtons());
        execute(msg);
    }

    private void sendAbout(long chatId) throws TelegramApiException {
        String text = "Выполняем кровельные работы скатных кровель любой сложности.\n\n" +
                "Строим надежные энергоэффективные каркасные дома для ПМЖ.\n\n" +
                "- Лучшие цены на кровельный материал и сухую строганую доску.\n" +
                "- Гарантия 10 лет.\n" +
                "- Оплата поэтапная по факту выполненных работ.\n" +
                "- Работаем без посредников, только напрямую с собственником.\n\n" +
                "✅ Смета и консультации бесплатно, выезды для обследования объекта по согласованию (по ТиНАО бесплатно).\n\n" +
                "⚒️ Как работаем : выезд → осмотр/диагностика → фотофиксация → смета → договор → поставка материала → монтаж → уборка → приёмка.\n\n" +
                "С уважением, Дмитрий (прораб, строительное образование ПГС МГСУ).\n" +
                "Звоните, задавайте вопросы.\n" +
                "<code>+7 985 731-85-85</code>";
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode(ParseMode.HTML);
        msg.setReplyMarkup(menuOnlyButtons());
        execute(msg);
    }

    private void sendContact(long chatId) throws TelegramApiException {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        InlineKeyboardButton tg = new InlineKeyboardButton();
        tg.setText("Написать в ТГ");
        tg.setUrl("https://t.me/Dmitry79857318585");
        rows.add(List.of(tg));

        InlineKeyboardButton call = new InlineKeyboardButton();
        call.setText("Позвонить по телефону");
        call.setCallbackData("call_phone");
        rows.add(List.of(call));

        rows.add(List.of(callbackButton("Вернуться назад", "menu")));

        keyboard.setKeyboard(rows);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("Связь с прорабом:");
        msg.setReplyMarkup(keyboard);
        execute(msg);
    }

    private void sendPhoneCard(long chatId) throws TelegramApiException {
        SendContact contact = new SendContact();
        contact.setChatId(String.valueOf(chatId));
        contact.setPhoneNumber("+79857318585");
        contact.setFirstName("Дмитрий");
        contact.setLastName("Прораб");
        execute(contact);
    }

    private void cacheStartPhotoId(Message message) {
        if (message == null || message.getPhoto() == null || message.getPhoto().isEmpty()) {
            return;
        }
        PhotoSize best = message.getPhoto().stream()
                .max((a, b) -> Integer.compare(a.getFileSize(), b.getFileSize()))
                .orElse(null);
        if (best == null || best.getFileId() == null) {
            return;
        }
        try {
            db.upsertMediaFileId(START_PHOTO_CACHE_KEY, best.getFileId());
        } catch (Exception e) {
            log.warn("Failed to cache media id", e);
        }
    }

    private void sendAdminMenu(long chatId) throws TelegramApiException {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(callbackButton("📋 Последние 5 заявок", "admin_last5")));
        rows.add(List.of(callbackButton("📊 Статистика", "admin_stats")));
        rows.add(List.of(callbackButton("⬅️ Назад", "admin_back")));
        keyboard.setKeyboard(rows);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("Админ панель:");
        msg.setReplyMarkup(keyboard);
        execute(msg);
    }

    private void sendAdminLastLeads(long chatId) throws TelegramApiException {
        List<Lead> leads;
        try {
            leads = db.getLastLeads(5);
        } catch (Exception e) {
            log.error("Failed to load leads", e);
            sendSimple(chatId, "Не удалось загрузить заявки.");
            return;
        }
        if (leads.isEmpty()) {
            sendSimple(chatId, "Заявок пока нет.");
            return;
        }
        StringBuilder sb = new StringBuilder("<b>Последние заявки</b>\n\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (int i = 0; i < leads.size(); i++) {
            Lead lead = leads.get(i);
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lead.createdAt()), ZoneId.systemDefault());
            sb.append(i + 1).append(") ")
                    .append(lead.name()).append(" — ")
                    .append(lead.contactMethod()).append("\n")
                    .append("📞 ").append(lead.phone() == null ? "—" : lead.phone()).append("\n")
                    .append("📧 ").append(lead.email() == null ? "—" : lead.email()).append("\n")
                    .append("🛠 ").append(lead.q1()).append("\n")
                    .append("🏠 ").append(lead.q2()).append("\n")
                    .append("📐 ").append(lead.q3()).append("\n")
                    .append("🕒 ").append(fmt.format(dt)).append("\n\n");
        }
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(sb.toString());
        msg.setParseMode(ParseMode.HTML);
        execute(msg);
    }

    private void sendAdminStats(long chatId) throws TelegramApiException {
        Stats stats;
        try {
            stats = db.getStats();
        } catch (Exception e) {
            log.error("Failed to load stats", e);
            sendSimple(chatId, "Не удалось загрузить статистику.");
            return;
        }
        String text = "<b>Статистика</b>\n" +
                "Всего заявок: " + stats.total() + "\n" +
                "Сегодня: " + stats.today();
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode(ParseMode.HTML);
        execute(msg);
    }

    private void sendQuestion(long chatId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setReplyMarkup(keyboard);
        execute(msg);
    }

    private InlineKeyboardMarkup q1Buttons() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(callbackButton("Строительство крыши с нуля", "q1_1")));
        rows.add(List.of(callbackButton("Монтаж кровли", "q1_2")));
        rows.add(List.of(callbackButton("Замена старой кровли", "q1_3")));
        rows.add(List.of(callbackButton("Утеплить кровлю", "q1_4")));
        rows.add(List.of(callbackButton("Монтаж кровельных аксессуаров", "q1_5")));
        rows.add(List.of(callbackButton("Другое", "q1_6")));
        rows.add(List.of(callbackButton(MENU_TEXT, "menu")));
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardMarkup q2Buttons() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(callbackButton("Металлочерепица", "q2_1")));
        rows.add(List.of(callbackButton("Мягкая кровля", "q2_2")));
        rows.add(List.of(callbackButton("Композитная черепица", "q2_3")));
        rows.add(List.of(callbackButton("Керамическая черепица", "q2_4")));
        rows.add(List.of(callbackButton("Ондулин", "q2_5")));
        rows.add(List.of(callbackButton("Другое", "q2_6")));
        rows.add(List.of(callbackButton(MENU_TEXT, "menu")));
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardMarkup q3Buttons() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(callbackButton("до 100 м кв", "q3_1")));
        rows.add(List.of(callbackButton("100-200 м кв", "q3_2")));
        rows.add(List.of(callbackButton("200-300 м кв", "q3_3")));
        rows.add(List.of(callbackButton("более 300м кв", "q3_4")));
        rows.add(List.of(callbackButton("затрудняюсь ответить", "q3_5")));
        rows.add(List.of(callbackButton(MENU_TEXT, "menu")));
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardMarkup contactButtons() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(callbackButton("Телеграмм", "contact_tg")));
        rows.add(List.of(callbackButton("Телефон", "contact_phone")));
        rows.add(List.of(callbackButton("Почта", "contact_email")));
        rows.add(List.of(callbackButton(MENU_TEXT, "menu")));
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardMarkup menuOnlyButtons() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(callbackButton(MENU_TEXT, "menu")));
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardButton callbackButton(String text, String data) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        btn.setCallbackData(data);
        return btn;
    }

    private void answerCallback(String callbackId) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackId);
        try {
            execute(answer);
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback", e);
        }
    }

    private String q1Label(String data) {
        return switch (data) {
            case "q1_1" -> "Строительство крыши с нуля";
            case "q1_2" -> "Монтаж кровли";
            case "q1_3" -> "Замена старой кровли";
            case "q1_4" -> "Утеплить кровлю";
            case "q1_5" -> "Монтаж кровельных аксессуаров";
            case "q1_6" -> "Другое";
            default -> "Не указано";
        };
    }

    private String q2Label(String data) {
        return switch (data) {
            case "q2_1" -> "Металлочерепица";
            case "q2_2" -> "Мягкая кровля";
            case "q2_3" -> "Композитная черепица";
            case "q2_4" -> "Керамическая черепица";
            case "q2_5" -> "Ондулин";
            case "q2_6" -> "Другое";
            default -> "Не указано";
        };
    }

    private String q3Label(String data) {
        return switch (data) {
            case "q3_1" -> "до 100 м кв";
            case "q3_2" -> "100-200 м кв";
            case "q3_3" -> "200-300 м кв";
            case "q3_4" -> "более 300м кв";
            case "q3_5" -> "затрудняюсь ответить";
            default -> "Не указано";
        };
    }

    private String stageName(String step) {
        return switch (step) {
            case STEP_Q1 -> "Вопрос 1: Что необходимо сделать";
            case STEP_Q2 -> "Вопрос 2: Тип кровли";
            case STEP_Q3 -> "Вопрос 3: Площадь кровли";
            case STEP_CONTACT -> "Выбор способа связи";
            case STEP_NAME -> "Ввод имени";
            case STEP_PHONE -> "Ввод телефона";
            case STEP_EMAIL -> "Ввод e-mail";
            default -> "Неизвестный этап";
        };
    }

    private String contactLabel(String data) {
        return switch (data) {
            case "contact_tg" -> "Телеграмм";
            case "contact_phone" -> "Телефон";
            case "contact_email" -> "Почта";
            default -> "Не указано";
        };
    }

    private void notifyAdminsNewLead(UserProfile profile, Session session) {
        String username = profile.username() == null ? "нет" : "@" + profile.username();
        String fullName = (profile.firstName() == null ? "" : profile.firstName()) +
                (profile.lastName() == null ? "" : (" " + profile.lastName()));
        if (fullName.isBlank()) {
            fullName = "Клиент";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<b>🧾 НОВАЯ ЗАЯВКА</b>\n")
                .append("👤 Клиент: ")
                .append("<a href=\"tg://user?id=").append(profile.userId()).append("\">")
                .append(fullName.trim())
                .append("</a>\n")
                .append("🏷 Тег: ").append(username).append("\n")
                .append("✍️ Имя (из анкеты): ").append(session.name()).append("\n")
                .append("📡 Способ связи: ").append(session.contactMethod()).append("\n")
                .append("📞 Телефон: ").append(session.phone() == null ? "—" : session.phone()).append("\n")
                .append("📧 Почта: ").append(session.email() == null ? "—" : session.email()).append("\n")
                .append("🛠 Что нужно: ").append(session.q1()).append("\n")
                .append("🏠 Тип кровли: ").append(session.q2()).append("\n")
                .append("📐 Площадь: ").append(session.q3());

        sendToAdmins(sb.toString());
    }

    private void notifyAdminsAbandoned(UserProfile profile, String stage) {
        String username = profile.username() == null ? "нет" : "@" + profile.username();
        String fullName = (profile.firstName() == null ? "" : profile.firstName()) +
                (profile.lastName() == null ? "" : (" " + profile.lastName()));
        if (fullName.isBlank()) {
            fullName = "Клиент";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<b>⚠️ Клиент вышел из расчёта</b>\n")
                .append("👤 Клиент: ")
                .append("<a href=\"tg://user?id=").append(profile.userId()).append("\">")
                .append(fullName.trim())
                .append("</a>\n")
                .append("🏷 Тег: ").append(username).append("\n")
                .append("🧭 Этап: ").append(stage);

        sendToAdmins(sb.toString());
    }

    private void sendToAdmins(String htmlText) {
        for (Long adminId : config.adminIds()) {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(adminId));
            msg.setText(htmlText);
            msg.setParseMode(ParseMode.HTML);
            try {
                execute(msg);
            } catch (TelegramApiException e) {
                log.error("Failed to notify admin {}", adminId, e);
            }
        }
    }

    private void sendSimple(long chatId, String text) throws TelegramApiException {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        execute(msg);
    }

    private void checkAbandonedSessions() {
        try {
            List<AbandonedSession> abandoned = db.findAbandonedToNotify(TimeUnit.MINUTES.toMillis(3));
            for (AbandonedSession session : abandoned) {
                UserProfile profile = new UserProfile(
                        session.userId(),
                        session.tgUsername(),
                        session.tgFirstName() == null ? "Клиент" : session.tgFirstName(),
                        session.tgLastName()
                );
                notifyAdminsAbandoned(profile, session.stage());
                db.markAbandonedNotified(session.userId());
            }
        } catch (Exception e) {
            log.error("Failed to check abandoned sessions", e);
        }
    }
}
