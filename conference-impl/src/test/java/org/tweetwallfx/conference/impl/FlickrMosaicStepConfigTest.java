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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tweetwallfx.cache.URLContent;
import org.tweetwallfx.controls.mosaic.LayoutType;
import org.tweetwallfx.stepengine.dataproviders.ImageStorage;
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
    void parsesSelectionThresholdOptions() {
        final FlickrMosaicStep.Config config = JsonDataConverter.convertFromString(
                "{\"selectionThresholdPercentage\":0.75,\"selectionThresholdHours\":48}",
                FlickrMosaicStep.Config.class);

        assertThat(config.selectionThresholdPercentage).isEqualTo(0.75);
        assertThat(config.selectionThresholdHours).isEqualTo(48L);
    }

    @Test
    void rejectsUnknownConfigOptions() {
        assertThatThrownBy(() -> JsonDataConverter.convertFromString(
                "{\"layoutTypo\":\"COVER_GRID\"}", FlickrMosaicStep.Config.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void filtersImagesWhenThresholdMet() {
        final FlickrMosaicStep.Config config = new FlickrMosaicStep.Config();
        config.selectionThresholdPercentage = 0.6;
        config.selectionThresholdHours = 24L;

        final FlickrMosaicStep step = new FlickrMosaicStep(config);
        final Instant now = Instant.parse("2026-10-11T12:00:00Z");

        final ImageStorage recent1 = ImageStorage.builder(
                now.minus(Duration.ofHours(2))).from(() -> URLContent.NO_CONTENT).build();
        final ImageStorage recent2 = ImageStorage.builder(
                now.minus(Duration.ofHours(10))).from(() -> URLContent.NO_CONTENT).build();
        final ImageStorage oldImage = ImageStorage.builder(
                now.minus(Duration.ofDays(5))).from(() -> URLContent.NO_CONTENT).build();

        // 2 out of 3 = 66.7% >= 60%
        final List<ImageStorage> images = List.of(recent1, recent2, oldImage);
        final List<ImageStorage> filtered = step.filterImagesForMosaic(images, now);

        assertThat(filtered).containsExactly(recent1, recent2);
    }

    @Test
    void keepsAllImagesWhenThresholdNotMet() {
        final FlickrMosaicStep.Config config = new FlickrMosaicStep.Config();
        config.selectionThresholdPercentage = 0.8;
        config.selectionThresholdHours = 24L;

        final FlickrMosaicStep step = new FlickrMosaicStep(config);
        final Instant now = Instant.parse("2026-10-11T12:00:00Z");

        final ImageStorage recent1 = ImageStorage.builder(
                now.minus(Duration.ofHours(2))).from(() -> URLContent.NO_CONTENT).build();
        final ImageStorage oldImage1 = ImageStorage.builder(
                now.minus(Duration.ofDays(2))).from(() -> URLContent.NO_CONTENT).build();
        final ImageStorage oldImage2 = ImageStorage.builder(
                now.minus(Duration.ofDays(5))).from(() -> URLContent.NO_CONTENT).build();

        // 1 out of 3 = 33.3% < 80%
        final List<ImageStorage> images = List.of(recent1, oldImage1, oldImage2);
        final List<ImageStorage> filtered = step.filterImagesForMosaic(images, now);

        assertThat(filtered).isSameAs(images);
    }

    @Test
    void keepsAllImagesWhenDisabled() {
        final FlickrMosaicStep.Config config = new FlickrMosaicStep.Config();
        config.selectionThresholdPercentage = null;
        config.selectionThresholdHours = 24L;

        final FlickrMosaicStep step = new FlickrMosaicStep(config);
        final Instant now = Instant.parse("2026-10-11T12:00:00Z");

        final ImageStorage recent = ImageStorage.builder(
                now.minus(Duration.ofHours(2))).from(() -> URLContent.NO_CONTENT).build();
        final ImageStorage oldImage = ImageStorage.builder(
                now.minus(Duration.ofDays(2))).from(() -> URLContent.NO_CONTENT).build();

        final List<ImageStorage> images = List.of(recent, oldImage);
        final List<ImageStorage> filtered = step.filterImagesForMosaic(images, now);

        assertThat(filtered).isSameAs(images);
    }
}
