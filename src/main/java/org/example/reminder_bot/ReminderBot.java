package org.example.reminder_bot;

import lombok.SneakyThrows;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class ReminderBot extends TelegramLongPollingBot {

    private final Map<String, ScheduledFuture<?>> activeReminders = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @SneakyThrows
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            String input = message.getText();
            String chatId = message.getChatId().toString();


            if (input.startsWith("/date")) {
                handleDateCommand(chatId);
                return;

            }


            if (input.startsWith("/world")) {
                handleWorldCommand(chatId);
                return;
            }


            if (input.startsWith("/remindme")) {
                handleReminderCommand(input, chatId);
                return;
            }


            sendMessage(chatId, "I can remind you! Use /remindme [seconds] [message].");
        } else if (update.hasCallbackQuery()) {

            handleStopReminder(update);


            if (update.getCallbackQuery().getData().startsWith("world_time_")) {
                handleCountryTime(update);
            }
        }
    }

    private void handleCountryTime(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        String countryName = callbackData.replace("world_time_", "");

        try {
            Country country = Country.valueOf(countryName);
            String timeZoneId = country.getTimeZone();
            String flag = country.getFlag();

            TimeZone timeZone = TimeZone.getTimeZone(timeZoneId);
            Calendar calendar = Calendar.getInstance(timeZone);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            dateFormat.setTimeZone(timeZone);
            String countryTime = dateFormat.format(calendar.getTime());


            sendMessage(update.getCallbackQuery().getMessage().getChatId().toString(),
                    "Current time in " + flag + " " + country.name() + ": " + countryTime);
        } catch (IllegalArgumentException e) {
            sendMessage(update.getCallbackQuery().getMessage().getChatId().toString(),
                    "Time zone for the selected country is not available.");
        }
    }

    private void handleWorldCommand(String chatId) {

        List<String> countries = new ArrayList<>();
        for (Country country : Country.values()) {
            countries.add(country.getFlag() + " " + country.name());
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();


        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 0; i < countries.size(); i++) {
            String country = countries.get(i);
            InlineKeyboardButton button = new InlineKeyboardButton(country);
            button.setCallbackData("world_time_" + country.replaceAll("[^a-zA-Z]", ""));

            row.add(button);


            if (row.size() == 3) {
                buttons.add(new ArrayList<>(row));
                row.clear();
            }
        }


        if (!row.isEmpty()) {
            buttons.add(row);
        }

        markup.setKeyboard(buttons);
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText("Choose a country to get the current time:");
        sendMessage.setReplyMarkup(markup);

        try {
            execute(sendMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleDateCommand(String chatId) {
        Date now = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentTime = dateFormat.format(now);
        sendMessage(chatId, "Current time: " + currentTime);
    }

    private void handleReminderCommand(String input, String chatId) {
        try {
            String[] parts = input.split(" ", 3);
            if (parts.length < 2) {
                sendMessage(chatId, "Invalid format. Use: /remindme [seconds] [message]");
                return;
            }

            int intervalInSeconds = Integer.parseInt(parts[1]);
            String reminderMessage = parts.length > 2 ? parts[2] : "Reminder!";

            if (intervalInSeconds <= 0) {
                sendMessage(chatId, "Time must be greater than zero.");
                return;
            }

            scheduleRecurringReminder(intervalInSeconds, chatId, reminderMessage);
            sendMessage(chatId, " Reminder set every " + intervalInSeconds + " seconds.");
        } catch (Exception e) {
            sendMessage(chatId, "Invalid format. Use: /remindme [seconds] [message]");
        }
    }

    private void scheduleRecurringReminder(int intervalInSeconds, String chatId, String message) {
        if (activeReminders.containsKey(chatId)) {
            sendMessage(chatId, "You already have an active reminder. Stop it first using the button.");
            return;
        }

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> sendReminderWithStopButton(chatId, message),
                intervalInSeconds * 1000L,
                intervalInSeconds * 1000L,
                TimeUnit.MILLISECONDS);

        activeReminders.put(chatId, task);
    }

    private void sendReminderWithStopButton(String chatId, String message) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText("Reminder: " + message);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        InlineKeyboardButton stopButton = new InlineKeyboardButton("Stop Reminder");
        stopButton.setCallbackData("stop_reminder");

        markup.setKeyboard(Collections.singletonList(Collections.singletonList(stopButton)));
        sendMessage.setReplyMarkup(markup);

        try {
            execute(sendMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleStopReminder(Update update) {
        String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
        if ("stop_reminder".equals(update.getCallbackQuery().getData())) {
            stopReminder(chatId);
            try {
                execute(new DeleteMessage(chatId, update.getCallbackQuery().getMessage().getMessageId()));
            } catch (Exception e) {
                e.printStackTrace();
            }
            sendMessage(chatId, "Reminder stopped.");
        }
    }

    private void stopReminder(String chatId) {
        ScheduledFuture<?> task = activeReminders.remove(chatId);
        if (task != null) {
            task.cancel(true);
        }
    }

    private void sendMessage(String chatId, String text) {
        try {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText(text);
            execute(sendMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return keys.userName;
    }

    @Override
    public String getBotToken() {
        return keys.token;
    }
}
