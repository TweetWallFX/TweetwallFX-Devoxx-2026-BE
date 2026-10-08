/*
 * MIT License
 *
 * Copyright (c) 2016-2026 TweetWallFX
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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Transition;
import javafx.scene.CacheHint;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tweetwallfx.controls.WordleSkin;
import org.tweetwallfx.controls.mosaic.LayoutType;
import org.tweetwallfx.controls.mosaic.MosaicArea;
import org.tweetwallfx.controls.mosaic.MosaicItem;
import org.tweetwallfx.controls.mosaic.MosaicItemSource;
import org.tweetwallfx.controls.mosaic.MosaicLayouts;
import org.tweetwallfx.controls.mosaic.MosaicTile;
import org.tweetwallfx.stepengine.api.DataProvider;
import org.tweetwallfx.stepengine.api.Step;
import org.tweetwallfx.stepengine.api.StepEngine.MachineContext;
import org.tweetwallfx.stepengine.api.config.AbstractConfig;
import org.tweetwallfx.stepengine.api.config.StepEngineSettings;
import org.tweetwallfx.stepengine.dataproviders.ImageStorage;
import org.tweetwallfx.transitions.LocationTransition;
import org.tweetwallfx.transitions.SizeTransition;

public class FlickrMosaicStep implements Step {

    private static final Logger LOG = LoggerFactory.getLogger(FlickrMosaicStep.class);

    /**
     * Upper bound on the number of non-matching images decoded while looking
     * for a requested orientation. Decoding is expensive, so a source that
     * cannot satisfy the request gives up after this many probes instead of
     * loading the entire pool.
     */
    private static final int MAX_ORIENTATION_SCAN = 128;

    /**
     * Multiplier applied to the mosaic cell count to size the image pool of the
     * aspect preserving layouts. Justified and cover layouts mix orientations
     * and need spare images so every band can be completed at the configured
     * shape; the historic matrix layout keeps the exact configured count.
     */
    private static final int NON_MATRIX_POOL_FACTOR = 4;

    private final Config config;
    private static final Random RANDOM = new SecureRandom();
    private final List<ImageView> tiles = new ArrayList<>();
    private final List<MosaicTile> mosaicTiles = new ArrayList<>();
    private final Set<Integer> highlightedIndexes = new HashSet<>();
    private Pane pane;
    private int count = 0;

    private FlickrMosaicStep(Config config) {
        this.config = config;
    }

    @Override
    public boolean shouldSkip(final MachineContext context) {
        boolean forceSkipping = null == config.skipWhenSkipped
                ? false
                : config.skipWhenSkipped.equals(context.get(Step.SKIP_TOKEN));
        boolean notEnoughImagesAvailable = context.getDataProvider(FlickrPhotoDataProvider.class)
                .getAccess().count() < config.getMinimumNumberOfImagesInCacheCalculated();

        boolean skip = forceSkipping || notEnoughImagesAvailable;
        return skip;
    }

    @Override
    public void doStep(final MachineContext context) {
        WordleSkin wordleSkin = (WordleSkin) context.get("WordleSkin");
        FlickrPhotoDataProvider dataProvider = context.getDataProvider(FlickrPhotoDataProvider.class);
        pane = wordleSkin.getPane();
        Transition createMosaicTransition = createMosaicTransition(dataProvider
                .getAccess()
                .getImages(config.getNumberOfImagesToChooseFromCalculated()));
        createMosaicTransition.setOnFinished(event
                -> executeAnimations(context));

        createMosaicTransition.play();
    }

    @Override
    public java.time.Duration preferredStepDuration(final MachineContext context) {
        return config.stepDuration();
    }

    private void executeAnimations(final MachineContext context) {
        if (tiles.isEmpty()) {
            context.proceed();
            return;
        }

        ImageWallAnimationTransition highlightAndZoomTransition
                = createHighlightAndZoomTransition();
        highlightAndZoomTransition.transition.play();
        highlightAndZoomTransition.transition.setOnFinished(event1 -> {
            Transition revert
                    = createReverseHighlightAndZoomTransition(highlightAndZoomTransition.tileIndex);
            revert.setDelay(Duration.seconds(3));
            revert.play();
            revert.setOnFinished(event -> {
                count++;
                if (count < config.numberOfHighlights) {
                    executeAnimations(context);
                } else {
                    count = 0;
                    ParallelTransition cleanup = new ParallelTransition();
                    for (final ImageView tile : tiles) {
                        FadeTransition ft = new FadeTransition(Duration.seconds(0.5), tile);
                        ft.setToValue(0);
                        cleanup.getChildren().addAll(ft);
                    }
                    cleanup.setOnFinished(cleanUpDown -> {
                        for (final ImageView tile : tiles) {
                            pane.getChildren().remove(tile);
                        }
                        tiles.clear();
                        mosaicTiles.clear();
                        highlightedIndexes.clear();
                        context.proceed();
                    });
                    cleanup.play();
                }
            });
        });
    }

    private ImageStorage getRandomImageStorage(final List<ImageStorage> distillingList, final List<ImageStorage> removedForTemporalCloseness) {
        final java.time.Duration durationBetweenImages = config.getDurationBetweenImages();
        final List<ImageStorage> selectionBase = distillingList.isEmpty()
                ? removedForTemporalCloseness
                : distillingList;
        final int index = RANDOM.nextInt(selectionBase.size());
        final ImageStorage selectedImageStorage = selectionBase.remove(index);

        // remove ImageStorage objects that are temporally too close(within config.secondsBetweenImages) to the selectedImageStorage
        final List<ImageStorage> temporallyCloseImageStorages = distillingList
                .stream()
                .filter(
                        is -> 0 <= durationBetweenImages
                                .compareTo(java.time.Duration.between(
                                        is.getTimestamp(),
                                        selectedImageStorage.getTimestamp()).abs())
                )
                .toList();
        temporallyCloseImageStorages.stream()
                .filter(Predicate.not(removedForTemporalCloseness::contains))
                .forEach(removedForTemporalCloseness::add);
        distillingList.removeAll(temporallyCloseImageStorages);

        return selectedImageStorage;
    }

    private Transition createMosaicTransition(final List<ImageStorage> imageStorages) {
        tiles.clear();
        mosaicTiles.clear();
        highlightedIndexes.clear();

        final SequentialTransition fadeIn = new SequentialTransition();
        final List<FadeTransition> allFadeIns = new ArrayList<>();
        final Duration individualFadeInTransitionDuration = Duration.seconds(config.determineActualIndividualFadeInDuration());
        final MosaicItemSource source = new LazyImageMosaicItemSource(imageStorages);

        for (final MosaicTile mosaicTile : MosaicLayouts.create(config.layoutType)
                .layout(source, createMosaicArea())) {
            final ImageView imageView = new ImageView((Image) mosaicTile.item().payload());
            imageView.setCache(true);
            imageView.setCacheHint(CacheHint.SPEED);
            imageView.setFitWidth(mosaicTile.width());
            imageView.setFitHeight(mosaicTile.height());
            imageView.setOpacity(0);
            imageView.setLayoutX(mosaicTile.x());
            imageView.setLayoutY(mosaicTile.y());
            tiles.add(imageView);
            mosaicTiles.add(mosaicTile);
            pane.getChildren().add(imageView);
            FadeTransition ft = new FadeTransition(individualFadeInTransitionDuration, imageView);
            ft.setToValue(1);
            allFadeIns.add(ft);
        }
        Collections.shuffle(allFadeIns, RANDOM);
        fadeIn.getChildren().addAll(allFadeIns);
        return fadeIn;
    }

    private MosaicArea createMosaicArea() {
        final double areaWidth = 0 != config.width ? config.width : pane.getWidth();
        final double areaHeight = 0 != config.height ? config.height : pane.getHeight();
        return new MosaicArea(
                Math.max(areaWidth, config.gapX + 1),
                Math.max(areaHeight, config.gapY + 1),
                config.layoutX,
                config.layoutY,
                config.gapX,
                config.gapY,
                config.columns,
                config.rows);
    }

    private ImageWallAnimationTransition createHighlightAndZoomTransition() {
        final int tileIndex = selectHighlightIndex();
        ImageView randomView = tiles.get(tileIndex);
        randomView.toFront();
        ParallelTransition firstParallelTransition = new ParallelTransition();
        ParallelTransition secondParallelTransition = new ParallelTransition();

        for (int i = 0; i < tiles.size(); i++) {
            if (i == tileIndex) {
                continue;
            }
            ImageView otherView = tiles.get(i);
            FadeTransition ft = new FadeTransition(Duration.seconds(1), otherView);
            ft.setToValue(0.3);
            firstParallelTransition.getChildren().add(ft);

            GaussianBlur blur = (GaussianBlur) otherView.getEffect();
            if (null == blur) {
                blur = new GaussianBlur(0);
                otherView.setEffect(blur);
            }
        }

        double maxWidth = (0 != config.width ? config.width : pane.getWidth()) * config.percentageForHighlightImage;
        double maxHeight = (0 != config.height ? config.height : pane.getHeight()) * config.percentageForHighlightImage;

        double realWidth = randomView.getImage().getWidth();
        double realHeight = randomView.getImage().getHeight();

        double scaleFactor = Math.min(maxWidth / realWidth, maxHeight / realHeight);

        double targetWidth = realWidth * scaleFactor;
        double targetheight = realHeight * scaleFactor;

        final SizeTransition zoomBox = new SizeTransition(Duration.seconds(config.resizeAndHighlightTransitionTime),
                randomView.fitWidthProperty(), randomView.fitHeightProperty())
                .withWidth(randomView.getLayoutBounds().getWidth(), targetWidth)
                .withHeight(randomView.getLayoutBounds().getHeight(), targetheight);
        final LocationTransition trans = new LocationTransition(Duration.seconds(config.resizeAndHighlightTransitionTime), randomView)
                .withX(randomView.getLayoutX(), (0 != config.width ? config.width : pane.getWidth()) / 2 - targetWidth / 2 + config.layoutX)
                .withY(randomView.getLayoutY(), (0 != config.height ? config.height : pane.getHeight()) / 2 - targetheight / 2 + config.layoutY);
        secondParallelTransition.getChildren().addAll(trans, zoomBox);

        SequentialTransition seqT = new SequentialTransition();
        seqT.getChildren().addAll(firstParallelTransition, secondParallelTransition);

        return new ImageWallAnimationTransition(seqT, tileIndex);
    }

    private int selectHighlightIndex() {
        if (tiles.size() <= 1) {
            return 0;
        }

        for (int attempt = 0; attempt < tiles.size(); attempt++) {
            final int index = RANDOM.nextInt(tiles.size());

            if (highlightedIndexes.add(index)) {
                return index;
            }
        }

        // every index has been highlighted already, start over
        highlightedIndexes.clear();
        final int index = RANDOM.nextInt(tiles.size());
        highlightedIndexes.add(index);
        return index;
    }

    private Transition createReverseHighlightAndZoomTransition(final int tileIndex) {
        ImageView randomView = tiles.get(tileIndex);
        randomView.toFront();
        ParallelTransition firstParallelTransition = new ParallelTransition();
        ParallelTransition secondParallelTransition = new ParallelTransition();

        for (int i = 0; i < tiles.size(); i++) {
            if (i == tileIndex) {
                continue;
            }
            FadeTransition ft = new FadeTransition(Duration.seconds(1), tiles.get(i));
            ft.setFromValue(0.3);
            ft.setToValue(1.0);
            firstParallelTransition.getChildren().add(ft);
        }

        final MosaicTile mosaicTile = mosaicTiles.get(tileIndex);

        final SizeTransition zoomBox = new SizeTransition(Duration.seconds(config.resizeAndHighlightTransitionTime),
                randomView.fitWidthProperty(), randomView.fitHeightProperty())
                .withWidth(randomView.getLayoutBounds().getWidth(), mosaicTile.width())
                .withHeight(randomView.getLayoutBounds().getHeight(), mosaicTile.height());
        final LocationTransition trans = new LocationTransition(Duration.seconds(config.resizeAndHighlightTransitionTime), randomView)
                .withX(randomView.getLayoutX(), mosaicTile.x())
                .withY(randomView.getLayoutY(), mosaicTile.y());
        secondParallelTransition.getChildren().addAll(trans, zoomBox);

        SequentialTransition seqT = new SequentialTransition();
        seqT.getChildren().addAll(secondParallelTransition, firstParallelTransition);

        secondParallelTransition.setOnFinished(event
                -> randomView.setEffect(null));

        return seqT;
    }

    private final class LazyImageMosaicItemSource implements MosaicItemSource {

        private final List<MosaicItem> decoded = new ArrayList<>();
        private final List<ImageStorage> distillingList = new ArrayList<>();
        private final List<ImageStorage> removedForTemporalCloseness = new ArrayList<>();

        private LazyImageMosaicItemSource(final List<ImageStorage> imageStorages) {
            distillingList.addAll(imageStorages);
            Collections.shuffle(distillingList, RANDOM);
        }

        @Override
        public Optional<MosaicItem> next() {
            while (true) {
                if (!decoded.isEmpty()) {
                    return Optional.of(decoded.remove(0));
                }

                final Optional<ImageStorage> storage = selectNext();

                if (storage.isEmpty()) {
                    return Optional.empty();
                }

                final Optional<MosaicItem> item = decode(storage.get());

                if (item.isPresent()) {
                    return item;
                }
            }
        }

        @Override
        public Optional<MosaicItem> nextMatching(final Predicate<MosaicItem> predicate) {
            for (int i = 0; i < decoded.size(); i++) {
                if (predicate.test(decoded.get(i))) {
                    return Optional.of(decoded.remove(i));
                }
            }

            int scanned = 0;

            while (scanned < MAX_ORIENTATION_SCAN) {
                final Optional<ImageStorage> storage = selectNext();

                if (storage.isEmpty()) {
                    break;
                }

                final Optional<MosaicItem> candidate = decode(storage.get());

                if (candidate.isEmpty()) {
                    scanned++;
                    continue;
                }

                if (predicate.test(candidate.get())) {
                    return candidate;
                }

                decoded.add(candidate.get());
                scanned++;
            }

            return Optional.empty();
        }

        @Override
        public int remaining() {
            return decoded.size() + distillingList.size() + removedForTemporalCloseness.size();
        }

        private Optional<ImageStorage> selectNext() {
            if (distillingList.isEmpty() && removedForTemporalCloseness.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(getRandomImageStorage(distillingList, removedForTemporalCloseness));
        }

        private Optional<MosaicItem> decode(final ImageStorage storage) {
            final Image image;

            try {
                image = storage.getImage();
            } catch (final RuntimeException e) {
                LOG.warn("Skipping image that failed to load", e);
                return Optional.empty();
            }

            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                LOG.warn("Skipping image without valid dimensions");
                return Optional.empty();
            }

            return Optional.of(new MosaicItem(image.getWidth(), image.getHeight(), image));
        }
    }

    private static class ImageWallAnimationTransition {

        private final Transition transition;
        private final int tileIndex;

        private ImageWallAnimationTransition(final Transition transition, final int tileIndex) {
            this.transition = transition;
            this.tileIndex = tileIndex;
        }
    }

    /**
     * Implementation of {@link Step.Factory} as Service implementation creating
     * {@link FlickrMosaicStep}.
     */
    public static final class FactoryImpl implements Step.Factory {

        @Override
        public FlickrMosaicStep create(final StepEngineSettings.StepDefinition stepDefinition) {
            final Config c = stepDefinition.getConfig(Config.class);
            LoggerFactory.getLogger(Factory.class).info("stepConfig: {}", c);
            return new FlickrMosaicStep(c);
        }

        @Override
        public Class<FlickrMosaicStep> getStepClass() {
            return FlickrMosaicStep.class;
        }

        @Override
        public Collection<Class<? extends DataProvider>> getRequiredDataProviders(final StepEngineSettings.StepDefinition stepSettings) {
            return Arrays.asList(FlickrPhotoDataProvider.class);
        }
    }

    public static class Config extends AbstractConfig {

        public double layoutX = 0D;
        public double layoutY = 0D;
        public double width = 0D;
        public double height = 0D;
        public int columns = 6;
        public int rows = 5;
        public LayoutType layoutType = LayoutType.MATRIX;
        public double gapX = 10D;
        public double gapY = 8D;

        private int countMosaicCells() {
            return columns * rows;
        }

        public String skipWhenSkipped;
        public int minimumNumberOfImagesInCache = -1;

        private int getMinimumNumberOfImagesInCacheCalculated() {
            return minimumNumberOfImagesInCache > 0
                    ? minimumNumberOfImagesInCache
                    : countMosaicCells() + Math.max(columns, rows);
        }

        public int secondsBetweenImages = 20;

        private java.time.Duration getDurationBetweenImages() {
            return java.time.Duration.ofSeconds(secondsBetweenImages);
        }

        public int numberOfImagesToChooseFrom = -1;
        public double numberOfImagesToChooseFromExtension = 1.4D;
        public int maxNumberOfImagesToChooseFrom = -1;

        private int getNumberOfImagesToChooseFromCalculated() {
            if (maxNumberOfImagesToChooseFrom > 0) {
                return maxNumberOfImagesToChooseFrom;
            }

            final int chosen = numberOfImagesToChooseFrom > 0
                    ? numberOfImagesToChooseFrom
                    : (int) (numberOfImagesToChooseFromExtension * countMosaicCells());

            return layoutType == LayoutType.MATRIX
                    ? chosen
                    : Math.max(chosen, NON_MATRIX_POOL_FACTOR * countMosaicCells());
        }

        public double percentageForHighlightImage = 0.8D;
        public double resizeAndHighlightTransitionTime = 2.5D;
        public int numberOfHighlights = 3;

        public double maxCumulativeFadeInDuration = 6D;
        public double maxIndividualFadeInDuration = 0.3D;
        public double minIndividualFadeInDuration = 0.1D;

        private double determineActualIndividualFadeInDuration() {
            double individualFadeInDurationForMaxCumulatative = maxCumulativeFadeInDuration / countMosaicCells();
            return Math.max(
                    minIndividualFadeInDuration,
                    Math.min(
                            maxIndividualFadeInDuration,
                            individualFadeInDurationForMaxCumulatative));
        }
    }
}
