package com.example.railtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.railtime.exception.InvalidTravelClassException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TravelClassTest {

    @Test
    void resolvesCodesCaseInsensitivelyAndTrimmed() {
        assertThat(TravelClass.fromCode("SL")).isEqualTo(TravelClass.SLEEPER);
        assertThat(TravelClass.fromCode("sl")).isEqualTo(TravelClass.SLEEPER);
        assertThat(TravelClass.fromCode(" 3a ")).isEqualTo(TravelClass.THIRD_AC);
        assertThat(TravelClass.fromCode("1A")).isEqualTo(TravelClass.FIRST_AC);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "XX", "SLEEPER", "4A", "<script>"})
    void rejectsUnknownCodes(String code) {
        assertThatThrownBy(() -> TravelClass.fromCode(code))
                .isInstanceOf(InvalidTravelClassException.class)
                .hasMessageContaining("Allowed values: SL, 1A, 2A, 3A, CC, EC, 3E, FC, 2S")
                .hasMessageNotContaining("<");
    }

    @Test
    void categoriesMatchDocumentedTatkalClasses() {
        assertThat(codesIn(TatkalCategory.AC)).containsExactly("2A", "3A", "CC", "EC", "3E");
        assertThat(codesIn(TatkalCategory.NON_AC)).containsExactly("SL", "FC", "2S");
        assertThat(codesIn(TatkalCategory.NOT_LISTED)).containsExactly("1A");
    }

    @Test
    void labelCombinesNameAndCode() {
        assertThat(TravelClass.AC_THREE_ECONOMY.label()).isEqualTo("AC 3 Economy (3E)");
        assertThat(TravelClass.SECOND_SITTING.displayName()).isEqualTo("Second Sitting");
    }

    private static String[] codesIn(TatkalCategory category) {
        return Arrays.stream(TravelClass.values())
                .filter(tc -> tc.tatkalCategory() == category)
                .map(TravelClass::code)
                .toArray(String[]::new);
    }
}
