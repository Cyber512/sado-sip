# SadoSIP — JavaFX SIP Telefon

MIB uchun JavaFX asosidagi zamonaviy SIP soft-telefon.

## Xususiyatlar

- SIP over WebSocket (RFC 7118) — Asterisk/FreeSWITCH bilan ishlaydi
- Kiruvchi va chiquvchi qo'ng'iroqlar
- Blind transfer (REFER)
- Kutayotgan qo'ng'iroqlar navbati
- G.711 μ-law/a-law RTP audio
- Zamonaviy dark UI (JavaFX)

## Ishga tushirish

```bash
mvn javafx:run
```

## Jar fayl yaratish

```bash
mvn package
java -jar target/sado-sip-1.0.0-jar-with-dependencies.jar
```

## Konfiguratsiya

`SipManager.java` ichida:
- `USER` / `PASS` — SIP login/parol
- `SERVER` / `DOMAIN` — SIP server
- `WS_URL` — WebSocket manzil
