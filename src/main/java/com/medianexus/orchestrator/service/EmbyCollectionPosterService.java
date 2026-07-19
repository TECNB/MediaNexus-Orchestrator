package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyItem;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmbyCollectionPosterService {

    private static final Logger log = LoggerFactory.getLogger(EmbyCollectionPosterService.class);
    private static final int POSTER_WIDTH = 1000;
    private static final int POSTER_HEIGHT = 1500;
    private static final int MAX_SOURCE_POSTERS = 4;

    private final EmbyClient embyClient;

    public EmbyCollectionPosterService(EmbyClient embyClient) {
        this.embyClient = embyClient;
    }

    public boolean refreshCollectionPoster(String collectionId) {
        if (!StringUtils.hasText(collectionId)) {
            return false;
        }
        List<BufferedImage> sourcePosters = sourcePosters(collectionId);
        if (sourcePosters.isEmpty()) {
            log.warn("Collection poster generation skipped because no member Primary image is available collectionId={}",
                    collectionId);
            return false;
        }
        try {
            embyClient.uploadPrimaryImage(collectionId, encodeJpeg(compose(sourcePosters)));
            return true;
        } catch (RuntimeException | IOException exception) {
            log.warn("Collection poster generation failed collectionId={}", collectionId, exception);
            return false;
        }
    }

    private List<BufferedImage> sourcePosters(String collectionId) {
        List<BufferedImage> images = new ArrayList<>();
        for (EmbyItem item : embyClient.listCollectionVideoItems(collectionId)) {
            if (images.size() == MAX_SOURCE_POSTERS) {
                break;
            }
            if (item == null || !StringUtils.hasText(item.id())) {
                continue;
            }
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                        embyClient.getPrimaryImage(item.id())
                ));
                if (image != null) {
                    images.add(image);
                }
            } catch (RuntimeException | IOException exception) {
                log.warn("Collection member Primary image unavailable collectionId={} itemId={}",
                        collectionId, item.id());
            }
        }
        return images;
    }

    private BufferedImage compose(List<BufferedImage> sourcePosters) {
        BufferedImage result = new BufferedImage(
                POSTER_WIDTH,
                POSTER_HEIGHT,
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );
        graphics.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
        );
        if (sourcePosters.size() == 1) {
            drawCropped(graphics, sourcePosters.get(0), 0, 0, POSTER_WIDTH, POSTER_HEIGHT);
        } else if (sourcePosters.size() == 2) {
            drawCropped(graphics, sourcePosters.get(0), 0, 0, POSTER_WIDTH / 2, POSTER_HEIGHT);
            drawCropped(graphics, sourcePosters.get(1), POSTER_WIDTH / 2, 0,
                    POSTER_WIDTH / 2, POSTER_HEIGHT);
        } else if (sourcePosters.size() == 3) {
            drawCropped(graphics, sourcePosters.get(0), 0, 0, POSTER_WIDTH / 2, POSTER_HEIGHT);
            drawCropped(graphics, sourcePosters.get(1), POSTER_WIDTH / 2, 0,
                    POSTER_WIDTH / 2, POSTER_HEIGHT / 2);
            drawCropped(graphics, sourcePosters.get(2), POSTER_WIDTH / 2, POSTER_HEIGHT / 2,
                    POSTER_WIDTH / 2, POSTER_HEIGHT / 2);
        } else {
            int cellWidth = POSTER_WIDTH / 2;
            int cellHeight = POSTER_HEIGHT / 2;
            for (int index = 0; index < MAX_SOURCE_POSTERS; index++) {
                drawCropped(
                        graphics,
                        sourcePosters.get(index),
                        index % 2 * cellWidth,
                        index / 2 * cellHeight,
                        cellWidth,
                        cellHeight
                );
            }
        }
        graphics.dispose();
        return result;
    }

    private void drawCropped(
            Graphics2D graphics,
            BufferedImage source,
            int targetX,
            int targetY,
            int targetWidth,
            int targetHeight
    ) {
        double scale = Math.max(
                (double) targetWidth / source.getWidth(),
                (double) targetHeight / source.getHeight()
        );
        int sourceWidth = Math.max(1, (int) Math.round(targetWidth / scale));
        int sourceHeight = Math.max(1, (int) Math.round(targetHeight / scale));
        int sourceX = Math.max(0, (source.getWidth() - sourceWidth) / 2);
        int sourceY = Math.max(0, (source.getHeight() - sourceHeight) / 2);
        graphics.drawImage(
                source,
                targetX,
                targetY,
                targetX + targetWidth,
                targetY + targetHeight,
                sourceX,
                sourceY,
                Math.min(source.getWidth(), sourceX + sourceWidth),
                Math.min(source.getHeight(), sourceY + sourceHeight),
                null
        );
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }
}
