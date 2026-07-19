package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyItem;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmbyCollectionPosterServiceTest {

    private final EmbyClient embyClient = mock(EmbyClient.class);
    private final EmbyCollectionPosterService service = new EmbyCollectionPosterService(embyClient);

    @Test
    void uploadsRealFourPosterGridAsTheCollectionPrimaryImage() throws IOException {
        when(embyClient.listCollectionVideoItems("collection-1")).thenReturn(List.of(
                item("item-1"),
                item("item-2"),
                item("item-3"),
                item("item-4"),
                item("item-5")
        ));
        when(embyClient.getPrimaryImage("item-1")).thenReturn(solidImage(Color.RED));
        when(embyClient.getPrimaryImage("item-2")).thenReturn(solidImage(Color.GREEN));
        when(embyClient.getPrimaryImage("item-3")).thenReturn(solidImage(Color.BLUE));
        when(embyClient.getPrimaryImage("item-4")).thenReturn(solidImage(Color.YELLOW));

        boolean ready = service.refreshCollectionPoster("collection-1");

        assertThat(ready).isTrue();
        ArgumentCaptor<byte[]> poster = ArgumentCaptor.forClass(byte[].class);
        verify(embyClient).uploadPrimaryImage(
                org.mockito.ArgumentMatchers.eq("collection-1"),
                poster.capture()
        );
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(poster.getValue()));
        assertThat(image.getWidth()).isEqualTo(1000);
        assertThat(image.getHeight()).isEqualTo(1500);
        assertPixelNear(image, 250, 375, Color.RED);
        assertPixelNear(image, 750, 375, Color.GREEN);
        assertPixelNear(image, 250, 1125, Color.BLUE);
        assertPixelNear(image, 750, 1125, Color.YELLOW);
    }

    private EmbyItem item(String id) {
        return new EmbyItem(id, id, "Movie", null, null);
    }

    private byte[] solidImage(Color color) throws IOException {
        BufferedImage image = new BufferedImage(300, 450, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private void assertPixelNear(BufferedImage image, int x, int y, Color expected) {
        Color actual = new Color(image.getRGB(x, y));
        assertThat(actual.getRed()).isCloseTo(expected.getRed(), within(5));
        assertThat(actual.getGreen()).isCloseTo(expected.getGreen(), within(5));
        assertThat(actual.getBlue()).isCloseTo(expected.getBlue(), within(5));
    }

    private org.assertj.core.data.Offset<Integer> within(int value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
