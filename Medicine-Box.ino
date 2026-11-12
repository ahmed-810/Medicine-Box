
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <Servo.h>
#include <EEPROM.h>
#include <string.h>
#include <stdio.h>
#include <ThreeWire.h>
#include <RtcDS1302.h>

// ==================================================
// MEDICINE BOX
// ARDUINO MEGA 2560 + DS1302 + HC-05
// ==================================================

#define BT Serial1

const unsigned long BT_BAUD = 9600;
const unsigned long BT_STATUS_INTERVAL_MS = 2000;

ThreeWire rtcWire(22, 23, 24);
RtcDS1302<ThreeWire> rtc(rtcWire);

LiquidCrystal_I2C lcd(0x27, 16, 2);

// Pins
const byte SERVO_PINS[4] = {3, 5, 6, 9};
const byte BUTTON_PINS[4] = {2, 4, 7, 8};
const byte DOSE_LED_PINS[4] = {A0, A1, A2, A3};

const byte BUZZER_PIN = 10;
const byte STATUS_LED_PINS[2] = {11, 13};
const byte SETTINGS_BUTTON_PIN = 12;

const byte CLOSED_ANGLE = 0;
const byte OPEN_ANGLE = 90;

// Timing
const unsigned long DEBOUNCE_MS = 40;
const unsigned long MESSAGE_INTERVAL_MS = 10000;
const unsigned long MESSAGE_DURATION_MS = 1000;
const unsigned long BUZZER_INTERVAL_MS = 350;

const uint32_t MISSED_AFTER_SECONDS =
    24UL * 60UL * 60UL;

// ==================================================
// DOSE SETTINGS
// ==================================================

Servo servos[4];

const byte DEFAULT_HOURS[4] = {8, 12, 18, 22};

byte doseHour[4] = {8, 12, 18, 22};
byte doseMinute[4] = {0, 0, 0, 0};

byte doseCount = 4;

bool pending[4] = {false, false, false, false};
bool servoOpen[4] = {false, false, false, false};

uint32_t dueEpoch[4] = {0, 0, 0, 0};

int lastTriggerDay[4] = {-1, -1, -1, -1};
int lastTriggerMonth[4] = {-1, -1, -1, -1};
int lastTriggerYear[4] = {-1, -1, -1, -1};

// ==================================================
// EEPROM
// ==================================================

const int EEPROM_MAGIC_ADDRESS = 0;
const int EEPROM_COUNT_ADDRESS = 1;
const int EEPROM_SETTINGS_ADDRESS = 2;

const byte EEPROM_MAGIC = 0x5A;

const int LOG_MAGIC_ADDR = 20;
const int LOG_NEXT_ADDR = 22;
const int LOG_START_ADDR = 23;

const byte LOG_MAGIC = 0xB7;
const byte LOG_CAPACITY = 40;
const byte LOG_RECORD_SIZE = 8;

const byte EVENT_TAKEN = 1;
const byte EVENT_MISSED = 2;

// ==================================================
// BUTTONS
// ==================================================

bool stableState[5] = {HIGH, HIGH, HIGH, HIGH, HIGH};
bool lastReading[5] = {HIGH, HIGH, HIGH, HIGH, HIGH};

unsigned long lastChange[5] = {0, 0, 0, 0, 0};

// ==================================================
// DISPLAY AND SETTINGS
// ==================================================

bool settingsMode = false;
byte settingsField = 0;
bool settingsChanged = false;
bool displayNeedsUpdate = true;

unsigned long lastMessageCycle = 0;
unsigned long messageStartedAt = 0;
unsigned long lastClockRefresh = 0;

bool showingMedicineMessage = false;
byte lastDisplayMode = 255;

// ==================================================
// ALARM
// ==================================================

unsigned long lastBuzzerToggle = 0;
bool buzzerState = false;

// ==================================================
// BLUETOOTH
// ==================================================

char btLine[64];
byte btLineLength = 0;

unsigned long lastBTStatus = 0;

// ==================================================
// TIME CONVERSION
// ==================================================

bool isLeapYear(uint16_t year) {
  return ((year % 4 == 0 && year % 100 != 0) ||
          (year % 400 == 0));
}

uint32_t rtcEpoch() {

  RtcDateTime dt = rtc.GetDateTime();

  uint32_t days = 0;

  for (uint16_t y = 1970; y < dt.Year(); y++) {
    days += isLeapYear(y) ? 366UL : 365UL;
  }

  const byte monthDays[12] = {
    31, 28, 31, 30, 31, 30,
    31, 31, 30, 31, 30, 31
  };

  for (byte m = 1; m < dt.Month(); m++) {

    days += monthDays[m - 1];

    if (m == 2 && isLeapYear(dt.Year())) {
      days++;
    }
  }

  days += dt.Day() - 1;

  return days * 86400UL
       + (uint32_t)dt.Hour() * 3600UL
       + (uint32_t)dt.Minute() * 60UL
       + dt.Second();
}

RtcDateTime epochToRtcDateTime(uint32_t epoch) {

  uint32_t days = epoch / 86400UL;
  uint32_t seconds = epoch % 86400UL;

  uint16_t year = 1970;

  while (true) {

    uint16_t daysInYear =
        isLeapYear(year) ? 366 : 365;

    if (days < daysInYear) break;

    days -= daysInYear;
    year++;
  }

  const byte monthDays[12] = {
    31, 28, 31, 30, 31, 30,
    31, 31, 30, 31, 30, 31
  };

  byte month = 1;

  while (month <= 12) {

    uint16_t daysInMonth = monthDays[month - 1];

    if (month == 2 && isLeapYear(year)) {
      daysInMonth++;
    }

    if (days < daysInMonth) break;

    days -= daysInMonth;
    month++;
  }

  byte day = (byte)days + 1;
  byte hour = seconds / 3600UL;
  byte minute = (seconds % 3600UL) / 60UL;
  byte second = seconds % 60UL;

  return RtcDateTime(
    year, month, day, hour, minute, second
  );
}

// ==================================================
// EEPROM SETTINGS
// ==================================================

void saveSettings() {

  EEPROM.update(EEPROM_MAGIC_ADDRESS, EEPROM_MAGIC);
  EEPROM.update(EEPROM_COUNT_ADDRESS, doseCount);

  for (byte i = 0; i < 4; i++) {

    EEPROM.update(
      EEPROM_SETTINGS_ADDRESS + i * 2,
      doseHour[i]
    );

    EEPROM.update(
      EEPROM_SETTINGS_ADDRESS + i * 2 + 1,
      doseMinute[i]
    );
  }

  settingsChanged = false;
}

void loadSettings() {

  if (EEPROM.read(EEPROM_MAGIC_ADDRESS) != EEPROM_MAGIC) {

    doseCount = 4;

    for (byte i = 0; i < 4; i++) {
      doseHour[i] = DEFAULT_HOURS[i];
      doseMinute[i] = 0;
    }

    saveSettings();
    return;
  }

  byte storedCount = EEPROM.read(EEPROM_COUNT_ADDRESS);

  if (storedCount >= 1 && storedCount <= 4) {
    doseCount = storedCount;
  } else {
    doseCount = 4;
  }

  for (byte i = 0; i < 4; i++) {

    byte h = EEPROM.read(
      EEPROM_SETTINGS_ADDRESS + i * 2
    );

    byte m = EEPROM.read(
      EEPROM_SETTINGS_ADDRESS + i * 2 + 1
    );

    if (h <= 23 && m <= 59) {

      doseHour[i] = h;
      doseMinute[i] = m;

    } else {

      doseHour[i] = DEFAULT_HOURS[i];
      doseMinute[i] = 0;
    }
  }
}

// ==================================================
// EVENT LOG
// ==================================================

void initLogIfNeeded() {

  if (EEPROM.read(LOG_MAGIC_ADDR) != LOG_MAGIC ||
      EEPROM.read(LOG_MAGIC_ADDR + 1) !=
          (byte)~LOG_MAGIC) {

    EEPROM.update(LOG_MAGIC_ADDR, LOG_MAGIC);

    EEPROM.update(
      LOG_MAGIC_ADDR + 1,
      (byte)~LOG_MAGIC
    );

    EEPROM.update(LOG_NEXT_ADDR, 0);

    for (byte i = 0; i < LOG_CAPACITY; i++) {

      int a = LOG_START_ADDR +
              (int)i * LOG_RECORD_SIZE;

      for (byte j = 0; j < LOG_RECORD_SIZE; j++) {
        EEPROM.update(a + j, 0);
      }
    }
  }

  if (EEPROM.read(LOG_NEXT_ADDR) >= LOG_CAPACITY) {
    EEPROM.update(LOG_NEXT_ADDR, 0);
  }
}

void writeLogEvent(
  byte doseIndex,
  byte eventType,
  uint32_t epoch
) {

  initLogIfNeeded();

  byte slot = EEPROM.read(LOG_NEXT_ADDR);

  int a = LOG_START_ADDR +
          (int)slot * LOG_RECORD_SIZE;

  EEPROM.update(a + 0, (byte)(epoch & 0xFF));
  EEPROM.update(a + 1, (byte)((epoch >> 8) & 0xFF));
  EEPROM.update(a + 2, (byte)((epoch >> 16) & 0xFF));
  EEPROM.update(a + 3, (byte)((epoch >> 24) & 0xFF));

  EEPROM.update(a + 4, doseIndex + 1);
  EEPROM.update(a + 5, eventType);
  EEPROM.update(a + 6, LOG_MAGIC);
  EEPROM.update(a + 7, (byte)~LOG_MAGIC);

  slot = (slot + 1) % LOG_CAPACITY;

  EEPROM.update(LOG_NEXT_ADDR, slot);
}

bool readLogEvent(
  byte slot,
  uint32_t &epoch,
  byte &doseNo,
  byte &eventType
) {

  if (slot >= LOG_CAPACITY) return false;

  int a = LOG_START_ADDR +
          (int)slot * LOG_RECORD_SIZE;

  if (EEPROM.read(a + 6) != LOG_MAGIC ||
      EEPROM.read(a + 7) != (byte)~LOG_MAGIC) {
    return false;
  }

  epoch = (uint32_t)EEPROM.read(a + 0)
        | ((uint32_t)EEPROM.read(a + 1) << 8)
        | ((uint32_t)EEPROM.read(a + 2) << 16)
        | ((uint32_t)EEPROM.read(a + 3) << 24);

  doseNo = EEPROM.read(a + 4);
  eventType = EEPROM.read(a + 5);

  return doseNo >= 1 && doseNo <= 4 &&
         (eventType == EVENT_TAKEN ||
          eventType == EVENT_MISSED);
}

void sendHistory() {

  uint32_t nowEpoch = rtcEpoch();

  uint32_t weekAgo =
      nowEpoch > 7UL * 86400UL
      ? nowEpoch - 7UL * 86400UL
      : 0;

  BT.println(F("HISTORY_BEGIN"));

  for (byte s = 0; s < LOG_CAPACITY; s++) {

    uint32_t ep;
    byte doseNo;
    byte eventType;

    if (readLogEvent(s, ep, doseNo, eventType) &&
        ep >= weekAgo && ep <= nowEpoch) {

      RtcDateTime dt = epochToRtcDateTime(ep);

      BT.print(F("HISTORY,"));
      BT.print(doseNo);
      BT.print(',');

      BT.print(
        eventType == EVENT_TAKEN
        ? F("TAKEN") : F("MISSED")
      );

      BT.print(',');

      BT.print(dt.Year());
      BT.print('-');

      if (dt.Month() < 10) BT.print('0');
      BT.print(dt.Month());

      BT.print('-');

      if (dt.Day() < 10) BT.print('0');
      BT.print(dt.Day());

      BT.print(',');

      if (dt.Hour() < 10) BT.print('0');
      BT.print(dt.Hour());

      BT.print(':');

      if (dt.Minute() < 10) BT.print('0');
      BT.print(dt.Minute());

      BT.print(',');

      BT.println((unsigned long)ep);
    }
  }

  BT.println(F("HISTORY_END"));
}

// ==================================================
// LCD HELPERS
// ==================================================

void printTwoDigits(byte value) {

  if (value < 10) lcd.print('0');

  lcd.print(value);
}

void clearLine(byte row) {

  lcd.setCursor(0, row);
  lcd.print(F("                "));
  lcd.setCursor(0, row);
}

bool anyPendingDose() {

  for (byte i = 0; i < 4; i++) {
    if (pending[i]) return true;
  }

  return false;
}

byte firstPendingDose() {

  for (byte i = 0; i < 4; i++) {
    if (pending[i]) return i;
  }

  return 4;
}

// ==================================================
// BLUETOOTH STATUS
// ==================================================

void btPrintStatus() {

  byte pendingMask = 0;
  byte servoMask = 0;

  for (byte i = 0; i < 4; i++) {

    if (pending[i]) pendingMask |= (1 << i);
    if (servoOpen[i]) servoMask |= (1 << i);
  }

  BT.print(F("STATUS,"));
  BT.print(doseCount);
  BT.print(',');
  BT.print(pendingMask);
  BT.print(',');
  BT.print(servoMask);

  // Send all four time pairs to preserve
  // compatibility with the existing app protocol.
  // doseCount tells the app how many are active.

  for (byte i = 0; i < 4; i++) {

    BT.print(',');
    BT.print(doseHour[i]);

    BT.print(',');
    BT.print(doseMinute[i]);
  }

  BT.println();
}

void btEvent(
  const __FlashStringHelper *name,
  byte i
) {

  BT.print(F("EVENT,"));
  BT.print(name);
  BT.print(',');
  BT.println(i + 1);
}

// ==================================================
// DOSE OPERATIONS
// ==================================================

void openServo(byte i) {

  if (i >= doseCount || pending[i]) return;

  servos[i].write(OPEN_ANGLE);

  servoOpen[i] = true;
  pending[i] = true;

  dueEpoch[i] = rtcEpoch();

  digitalWrite(DOSE_LED_PINS[i], HIGH);

  displayNeedsUpdate = true;

  btEvent(F("DUE"), i);
  btPrintStatus();
}

void closeServo(byte i) {

  if (i >= 4) return;

  servos[i].write(CLOSED_ANGLE);

  servoOpen[i] = false;
}

void confirmDose(byte i) {

  if (i >= 4 || !pending[i]) return;

  pending[i] = false;

  closeServo(i);

  digitalWrite(DOSE_LED_PINS[i], LOW);

  writeLogEvent(i, EVENT_TAKEN, rtcEpoch());

  displayNeedsUpdate = true;

  btEvent(F("TAKEN"), i);
  btPrintStatus();
}

void markOverdueMissed() {

  uint32_t nowEpoch = rtcEpoch();

  for (byte i = 0; i < 4; i++) {

    if (pending[i] &&
        dueEpoch[i] > 0 &&
        nowEpoch >= dueEpoch[i] &&
        nowEpoch - dueEpoch[i] >=
            MISSED_AFTER_SECONDS) {

      writeLogEvent(i, EVENT_MISSED, nowEpoch);

      btEvent(F("MISSED"), i);

      // Keep pending and alarm active.
      // Reset timer to prevent repeated logging
      // every loop.

      dueEpoch[i] = nowEpoch;
    }
  }
}

// ==================================================
// BLUETOOTH COMMANDS
// ==================================================

void processBTCommand(char *line) {

  while (*line == ' ' || *line == '\t') {
    line++;
  }

  if (!strcmp(line, "STATUS?") ||
      !strcmp(line, "STATUS")) {

    btPrintStatus();
    return;
  }

  if (!strcmp(line, "HISTORY?")) {

    sendHistory();
    return;
  }

  if (!strcmp(line, "HELP")) {

    BT.println(F("STATUS?"));
    BT.println(F("HISTORY?"));
    BT.println(F("SETCOUNT,n"));
    BT.println(F("SETTIME,index,hour,minute"));
    BT.println(F("CONFIRM,index"));

    return;
  }

  int a, b, c;

  // Set dose count

  if (sscanf(line, "SETCOUNT,%d", &a) == 1) {

    if (a >= 1 && a <= 4) {

      doseCount = (byte)a;

      saveSettings();

      displayNeedsUpdate = true;

      BT.println(F("OK,SETCOUNT"));
      btPrintStatus();

    } else {

      BT.println(F("ERR,COUNT_RANGE"));
    }

    return;
  }

  // Set dose time

  if (sscanf(
        line,
        "SETTIME,%d,%d,%d",
        &a, &b, &c
      ) == 3) {

    if (a >= 1 && a <= 4 &&
        b >= 0 && b <= 23 &&
        c >= 0 && c <= 59) {

      doseHour[a - 1] = (byte)b;
      doseMinute[a - 1] = (byte)c;

      saveSettings();

      displayNeedsUpdate = true;

      BT.println(F("OK,SETTIME"));
      btPrintStatus();

    } else {

      BT.println(F("ERR,TIME_RANGE"));
    }

    return;
  }

  // Confirm dose

  if (sscanf(line, "CONFIRM,%d", &a) == 1) {

    if (a >= 1 && a <= 4) {

      if (pending[a - 1]) {

        confirmDose(a - 1);

        BT.println(F("OK,CONFIRM"));

      } else {

        BT.println(F("ERR,NOT_PENDING"));
      }

      btPrintStatus();

    } else {

      BT.println(F("ERR,BOX_RANGE"));
    }

    return;
  }

  BT.println(F("ERR,UNKNOWN_COMMAND"));
}

void pollBluetooth() {

  while (BT.available() > 0) {

    char ch = (char)BT.read();

    if (ch == '\r') continue;

    if (ch == '\n') {

      btLine[btLineLength] = '\0';

      if (btLineLength > 0) {
        processBTCommand(btLine);
      }

      btLineLength = 0;

    } else if (btLineLength < sizeof(btLine) - 1) {

      btLine[btLineLength++] = ch;

    } else {

      btLineLength = 0;

      BT.println(F("ERR,LINE_TOO_LONG"));
    }
  }

  if (millis() - lastBTStatus >=
      BT_STATUS_INTERVAL_MS) {

    lastBTStatus = millis();

    btPrintStatus();
  }
}

// ==================================================
// BUTTON DEBOUNCE
// ==================================================

bool pressed(byte index, byte pin) {

  bool reading = digitalRead(pin);

  if (reading != lastReading[index]) {

    lastReading[index] = reading;
    lastChange[index] = millis();
  }

  if (millis() - lastChange[index] >= DEBOUNCE_MS) {

    if (reading != stableState[index]) {

      stableState[index] = reading;

      if (stableState[index] == LOW) {
        return true;
      }
    }
  }

  return false;
}

// ==================================================
// SCHEDULE CHECK
// Only selected doses are scheduled.
// ==================================================

void checkDoseTimes(const RtcDateTime &now) {

  for (byte i = 0; i < doseCount; i++) {

    if (pending[i]) continue;

    if (now.Hour() != doseHour[i] ||
        now.Minute() != doseMinute[i]) {

      continue;
    }

    bool alreadyTriggered =
      lastTriggerDay[i] == now.Day() &&
      lastTriggerMonth[i] == now.Month() &&
      lastTriggerYear[i] == now.Year();

    if (alreadyTriggered) continue;

    lastTriggerDay[i] = now.Day();
    lastTriggerMonth[i] = now.Month();
    lastTriggerYear[i] = now.Year();

    openServo(i);
  }
}

// ==================================================
// SETTINGS
// ==================================================

void enterSettings() {

  settingsMode = true;
  settingsField = 0;

  displayNeedsUpdate = true;
}

void exitSettings() {

  if (settingsChanged) {
    saveSettings();
  }

  settingsMode = false;

  displayNeedsUpdate = true;
  showingMedicineMessage = false;

  lastMessageCycle = millis();
  lastDisplayMode = 255;

  btPrintStatus();
}

void changeSetting(int direction) {

  // Field 0 = number of doses

  if (settingsField == 0) {

    int count = (int)doseCount + direction;

    if (count < 1) count = 4;
    if (count > 4) count = 1;

    doseCount = (byte)count;

    // Do not delete pending doses when
    // the selected count is reduced.
    // They can still be confirmed.

  } else {

    byte i = (settingsField - 1) / 2;

    // Do not edit inactive dose times
    if (i >= doseCount) return;

    if ((settingsField % 2) == 1) {

      int h = (int)doseHour[i] + direction;

      if (h < 0) h = 23;
      if (h > 23) h = 0;

      doseHour[i] = (byte)h;

    } else {

      int m = (int)doseMinute[i] + direction;

      if (m < 0) m = 59;
      if (m > 59) m = 0;

      doseMinute[i] = (byte)m;
    }
  }

  settingsChanged = true;
  displayNeedsUpdate = true;
}

// ==================================================
// SETTINGS DISPLAY
// ==================================================

void showSettingsScreen() {

  clearLine(0);
  clearLine(1);

  if (settingsField == 0) {

    lcd.setCursor(0, 0);
    lcd.print(F("Dose count: "));
    lcd.print(doseCount);

    lcd.setCursor(0, 1);
    lcd.print(F("B2+ B3- B4>"));

    return;
  }

  byte i = (settingsField - 1) / 2;

  if (i >= doseCount) {
    settingsField = doseCount * 2;
    i = (settingsField - 1) / 2;
  }

  lcd.setCursor(0, 0);

  lcd.print(F("Dose "));
  lcd.print(i + 1);

  lcd.print(
    (settingsField % 2) == 1
    ? F(" hour")
    : F(" minute")
  );

  lcd.setCursor(0, 1);

  printTwoDigits(doseHour[i]);

  lcd.print(':');

  printTwoDigits(doseMinute[i]);

  lcd.print(F(" B2+ B3-"));
}

// ==================================================
// SETTINGS BUTTONS
// ==================================================

void handleSettingsButtons() {

  byte maxField = doseCount * 2;

  // B1: Previous

  if (pressed(0, BUTTON_PINS[0])) {

    settingsField =
      (settingsField == 0)
      ? maxField
      : settingsField - 1;

    displayNeedsUpdate = true;
  }

  // B2: Increase

  if (pressed(1, BUTTON_PINS[1])) {

    changeSetting(1);
  }

  // B3: Decrease

  if (pressed(2, BUTTON_PINS[2])) {

    changeSetting(-1);
  }

  // B4: Next / Save

  if (pressed(3, BUTTON_PINS[3])) {

    if (settingsField >= doseCount * 2) {

      exitSettings();

    } else {

      settingsField++;

      displayNeedsUpdate = true;
    }
  }
}

// ==================================================
// LCD DISPLAY
// ==================================================

void showClock(const RtcDateTime &now) {

  clearLine(0);

  lcd.print(F("Time "));

  printTwoDigits(now.Hour());

  lcd.print(':');

  printTwoDigits(now.Minute());

  lcd.print(':');

  printTwoDigits(now.Second());

  clearLine(1);

  lcd.print(F("Date "));

  printTwoDigits(now.Day());

  lcd.print('/');

  printTwoDigits(now.Month());

  lcd.print('/');

  lcd.print(now.Year());
}

void showPendingDose() {

  byte i = firstPendingDose();

  if (i >= 4) return;

  clearLine(0);

  lcd.print(F("Dose "));
  lcd.print(i + 1);
  lcd.print(F(" NEED CONFIRM"));

  clearLine(1);

  lcd.print(F("Press button "));
  lcd.print(i + 1);
}

void updateDisplay(const RtcDateTime &now) {

  if (settingsMode) {

    if (displayNeedsUpdate) {

      lcd.clear();

      showSettingsScreen();

      displayNeedsUpdate = false;
    }

    return;
  }

  if (anyPendingDose()) {

    if (displayNeedsUpdate ||
        lastDisplayMode != 2) {

      lcd.clear();

      showPendingDose();

      lastDisplayMode = 2;

      displayNeedsUpdate = false;
    }

    return;
  }

  unsigned long t = millis();

  if (showingMedicineMessage) {

    if (t - messageStartedAt <
        MESSAGE_DURATION_MS) {

      return;
    }

    showingMedicineMessage = false;

    lastClockRefresh = 0;
    lastDisplayMode = 255;
  }

  if (t - lastMessageCycle >=
      MESSAGE_INTERVAL_MS) {

    showingMedicineMessage = true;

    messageStartedAt = t;
    lastMessageCycle = t;

    lcd.clear();

    lcd.setCursor(0, 0);

    lcd.print(F("  Medicine Box  "));

    lastDisplayMode = 1;

    return;
  }

  if (t - lastClockRefresh >= 500 ||
      lastDisplayMode != 0 ||
      displayNeedsUpdate) {

    lastClockRefresh = t;

    if (lastDisplayMode != 0) {
      lcd.clear();
    }

    showClock(now);

    lastDisplayMode = 0;

    displayNeedsUpdate = false;
  }
}

// ==================================================
// ALARM AND STATUS LEDS
// ==================================================

void updateAlarm() {

  bool alarmNeeded = anyPendingDose();

  digitalWrite(
    STATUS_LED_PINS[0],
    alarmNeeded ? HIGH : LOW
  );

  digitalWrite(
    STATUS_LED_PINS[1],
    alarmNeeded ? LOW : HIGH
  );

  if (!alarmNeeded) {

    noTone(BUZZER_PIN);

    buzzerState = false;

    return;
  }

  if (millis() - lastBuzzerToggle >=
      BUZZER_INTERVAL_MS) {

    lastBuzzerToggle = millis();

    buzzerState = !buzzerState;

    if (buzzerState) {

      tone(BUZZER_PIN, 2000);

    } else {

      noTone(BUZZER_PIN);
    }
  }
}

// ==================================================
// SETUP
// ==================================================

void setup() {

  BT.begin(BT_BAUD);

  Wire.begin();

  lcd.init();
  lcd.backlight();
  lcd.clear();

  for (byte i = 0; i < 4; i++) {

    pinMode(BUTTON_PINS[i], INPUT_PULLUP);

    pinMode(DOSE_LED_PINS[i], OUTPUT);

    digitalWrite(DOSE_LED_PINS[i], LOW);

    servos[i].attach(SERVO_PINS[i]);

    servos[i].write(CLOSED_ANGLE);
  }

  pinMode(SETTINGS_BUTTON_PIN, INPUT_PULLUP);

  pinMode(BUZZER_PIN, OUTPUT);

  for (byte i = 0; i < 2; i++) {

    pinMode(STATUS_LED_PINS[i], OUTPUT);

    digitalWrite(STATUS_LED_PINS[i], LOW);
  }

  loadSettings();

  initLogIfNeeded();

  // DS1302 initialization

  rtc.Begin();

  RtcDateTime compiled(__DATE__, __TIME__);

  if (rtc.GetIsWriteProtected()) {
    rtc.SetIsWriteProtected(false);
  }

  if (!rtc.IsDateTimeValid()) {
    rtc.SetDateTime(compiled);
  }

  if (!rtc.GetIsRunning()) {
    rtc.SetIsRunning(true);
  }

  lastMessageCycle = millis();

  lastClockRefresh = 0;

  displayNeedsUpdate = true;

  BT.println(F("MEDICINEBOX,READY"));

  btPrintStatus();
}

// ==================================================
// MAIN LOOP
// ==================================================

void loop() {

  pollBluetooth();

  RtcDateTime now = rtc.GetDateTime();

  // Settings button

  if (pressed(4, SETTINGS_BUTTON_PIN)) {

    if (settingsMode) {

      exitSettings();

    } else {

      enterSettings();
    }
  }

  // Settings mode

  if (settingsMode) {

    handleSettingsButtons();

    updateAlarm();

    updateDisplay(now);

    return;
  }

  // Confirm buttons
  // All four buttons can confirm an already pending dose,
  // including one that was pending before reducing doseCount.

  for (byte i = 0; i < 4; i++) {

    if (pressed(i, BUTTON_PINS[i]) &&
        pending[i]) {

      confirmDose(i);
    }
  }

  // Only selected doses are scheduled

  checkDoseTimes(now);

  markOverdueMissed();

  updateAlarm();

  updateDisplay(now);
}
