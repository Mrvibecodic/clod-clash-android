package com.github.kr328.clash.design.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки полей ввода: имя файла профиля, адрес подписки, интервал
 * автообновления. Всё это человек набирает руками, и ошибку надо ловить
 * до того, как она уедет в конфигурацию.
 *
 * `ValidatorAgeSecretKey` сюда не входит намеренно: он спрашивает ядро,
 * а ядра на JVM нет.
 */
class ValidatorTest {
    @Test
    fun `имя файла без разделителей пути`() {
        assertTrue(ValidatorFileName("config.yaml"))
        assertTrue(ValidatorFileName("моя подписка.yaml"))

        // Косая черта увела бы файл из каталога профиля.
        assertFalse(ValidatorFileName("sub/config.yaml"))
        assertFalse(ValidatorFileName("*.yaml"))
        assertFalse(ValidatorFileName("a%b"))
        assertFalse(ValidatorFileName("a&b"))
        assertFalse(ValidatorFileName("a\nb"))

        // Одни пробелы — не имя.
        assertFalse(ValidatorFileName("   "))
        assertFalse(ValidatorFileName(""))
    }

    @Test
    fun `адрес подписки только http и https`() {
        assertTrue(ValidatorHttpUrl("https://panel.example.com/sub"))
        assertTrue(ValidatorHttpUrl("http://panel.example.com/sub"))

        // Регистр схемы человек не обязан соблюдать.
        assertTrue(ValidatorHttpUrl("HTTPS://panel.example.com/sub"))

        assertFalse(ValidatorHttpUrl("panel.example.com/sub"))
        assertFalse(ValidatorHttpUrl("ftp://panel.example.com/sub"))
        assertFalse(ValidatorHttpUrl(""))
    }

    @Test
    fun `интервал автообновления не меньше четверти часа`() {
        // Пусто — «не обновлять», это законный ответ.
        assertTrue(ValidatorAutoUpdateInterval(""))
        assertTrue(ValidatorAutoUpdateInterval("15"))
        assertTrue(ValidatorAutoUpdateInterval("1440"))

        assertFalse(ValidatorAutoUpdateInterval("14"))
        assertFalse(ValidatorAutoUpdateInterval("0"))
        assertFalse(ValidatorAutoUpdateInterval("-60"))

        // Не число — тоже отказ, а не «ноль минут».
        assertFalse(ValidatorAutoUpdateInterval("часто"))
    }

    @Test
    fun `непустое значение`() {
        assertTrue(ValidatorNotBlank("x"))
        assertFalse(ValidatorNotBlank(""))
        assertFalse(ValidatorNotBlank("   "))
        assertTrue(ValidatorAcceptAll(""))
    }
}
