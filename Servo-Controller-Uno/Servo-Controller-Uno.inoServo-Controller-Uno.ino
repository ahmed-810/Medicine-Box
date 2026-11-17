#include <Servo.h>

// ==================================================
// MEDICINE BOX - ARDUINO UNO
// SERVO CONTROLLER ONLY
//
// Receives commands from the Mega through Serial RX (pin 0)
// Command format: S,index,angle
//   index : 0..3  (servo / box number - 1)
//   angle : 0 = close, any value > 0 = open
// Example: S,0,130
//
// The opening angle is decided here (OPEN_ANGLE),
// not by the angle sent from the Mega.
// ==================================================

const byte SERVO_PINS[4] = {3, 5, 6, 9};

const byte CLOSED_ANGLE = 0;
const byte OPEN_ANGLE = 130;

Servo servos[4];

char buf[24];
byte n = 0;

void setup() {

  Serial.begin(9600);

  for (byte i = 0; i < 4; i++) {

    servos[i].attach(SERVO_PINS[i]);

    servos[i].write(CLOSED_ANGLE);

    delay(150);
  }
}

void loop() {

  while (Serial.available() > 0) {

    char c = (char)Serial.read();

    if (c == '\r') continue;

    if (c == '\n') {

      buf[n] = '\0';

      n = 0;

      int i, angle;

      if (sscanf(buf, "S,%d,%d", &i, &angle) == 2 &&
          i >= 0 && i < 4 &&
          angle >= 0 && angle <= 180) {

        if (angle > CLOSED_ANGLE) {
          servos[i].write(OPEN_ANGLE);
        } else {
          servos[i].write(CLOSED_ANGLE);
        }
      }

    } else if (n < sizeof(buf) - 1) {

      buf[n++] = c;

    } else {

      n = 0;
    }
  }
}
