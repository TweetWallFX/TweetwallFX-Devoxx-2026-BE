/*
 * MIT License
 *
 * Copyright (c) 2026 TweetWallFX
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.tweetwallfx.conference.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tweetwallfx.controls.mosaic.LayoutType;
import org.tweetwallfx.util.JsonDataConverter;

class FlickrMosaicStepConfigTest {

    @Test
    void defaultsToMatrixLayoutWithoutExplicitOptions() {
        final FlickrMosaicStep.Config config = JsonDataConverter.convertFromString(
                "{}", FlickrMosaicStep.Config.class);

        assertThat(config.layoutType).isEqualTo(LayoutType.MATRIX);
        assertThat(config.gapX).isEqualTo(10D);
        assertThat(config.gapY).isEqualTo(8D);
        assertThat(config.maxNumberOfImagesToChooseFrom).isEqualTo(-1);
        assertThat(config.columns).isEqualTo(6);
        assertThat(config.rows).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(LayoutType.class)
    void mapsEveryLayoutTypeFromConfigString(final LayoutType layoutType) {
        final FlickrMosaicStep.Config config = JsonDataConverter.convertFromString(
                "{\"layoutType\":\"" + layoutType.name() + "\"}",
                FlickrMosaicStep.Config.class);

        assertThat(config.layoutType).isEqualTo(layoutType);
    }

    @Test
    void parsesNewTilingOptions() {
        final FlickrMosaicStep.Config config = JsonDataConverter.convertFromString(
                "{\"layoutType\":\"COVER_GRID\",\"gapX\":6.0,\"gapY\":4.0,\"maxNumberOfImagesToChooseFrom\":80}",
                FlickrMosaicStep.Config.class);

        assertThat(config.layoutType).isEqualTo(LayoutType.COVER_GRID);
        assertThat(config.gapX).isEqualTo(6.0);
        assertThat(config.gapY).isEqualTo(4.0);
        assertThat(config.maxNumberOfImagesToChooseFrom).isEqualTo(80);
    }

    @Test
    void rejectsUnknownConfigOptions() {
        assertThatThrownBy(() -> JsonDataConverter.convertFromString(
                "{\"layoutTypo\":\"COVER_GRID\"}", FlickrMosaicStep.Config.class))
                .isInstanceOf(IllegalStateException.class);
    }
}
