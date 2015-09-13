package edu.illinois.library.cantaloupe.processor;

import com.sun.media.jai.codec.ImageCodec;
import com.sun.media.jai.codec.ImageEncoder;
import com.sun.media.jai.codec.JPEGEncodeParam;
import com.sun.media.jai.codec.PNGEncodeParam;
import com.sun.media.jai.codecimpl.TIFFImageEncoder;
import edu.illinois.library.cantaloupe.image.SourceFormat;
import edu.illinois.library.cantaloupe.request.OutputFormat;
import edu.illinois.library.cantaloupe.request.Parameters;
import edu.illinois.library.cantaloupe.request.Quality;
import edu.illinois.library.cantaloupe.request.Region;
import edu.illinois.library.cantaloupe.request.Rotation;
import edu.illinois.library.cantaloupe.request.Size;
import org.restlet.data.MediaType;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.media.jai.ImageLayout;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.operator.TransposeDescriptor;
import javax.media.jai.ParameterBlockJAI;
import javax.media.jai.RenderedOp;
import java.awt.Dimension;
import java.awt.RenderingHints;
import java.awt.image.renderable.ParameterBlock;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Processor using the Java Advanced Imaging (JAI) framework.
 */
class JaiProcessor implements Processor {

    private static final int JAI_TILE_SIZE = 512;
    private static final Set<Quality> SUPPORTED_QUALITIES = new HashSet<Quality>();
    private static final Set<ProcessorFeature> SUPPORTED_FEATURES =
            new HashSet<ProcessorFeature>();

    private static HashMap<SourceFormat,Set<OutputFormat>> formatsMap;

    static {
        SUPPORTED_QUALITIES.add(Quality.BITONAL);
        SUPPORTED_QUALITIES.add(Quality.COLOR);
        SUPPORTED_QUALITIES.add(Quality.DEFAULT);
        SUPPORTED_QUALITIES.add(Quality.GRAY);

        SUPPORTED_FEATURES.add(ProcessorFeature.MIRRORING);
        SUPPORTED_FEATURES.add(ProcessorFeature.REGION_BY_PERCENT);
        SUPPORTED_FEATURES.add(ProcessorFeature.REGION_BY_PIXELS);
        SUPPORTED_FEATURES.add(ProcessorFeature.ROTATION_ARBITRARY);
        SUPPORTED_FEATURES.add(ProcessorFeature.ROTATION_BY_90S);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_ABOVE_FULL);
        //SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_WHITELISTED);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_FORCED_WIDTH_HEIGHT);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_HEIGHT);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_PERCENT);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_WIDTH);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_WIDTH_HEIGHT);
    }

    /**
     * @return Map of available output formats for all known source formats,
     * based on information reported by the ImageIO library.
     */
    public static HashMap<SourceFormat, Set<OutputFormat>> getFormats() {
        if (formatsMap == null) {
            final String[] readerMimeTypes = ImageIO.getReaderMIMETypes();
            final String[] writerMimeTypes = ImageIO.getWriterMIMETypes();
            formatsMap = new HashMap<SourceFormat, Set<OutputFormat>>();
            for (SourceFormat sourceFormat : SourceFormat.values()) {
                Set<OutputFormat> outputFormats = new HashSet<OutputFormat>();

                for (int i = 0, length = readerMimeTypes.length; i < length; i++) {
                    if (sourceFormat.getMediaTypes().
                            contains(new MediaType(readerMimeTypes[i].toLowerCase()))) {
                        for (OutputFormat outputFormat : OutputFormat.values()) {
                            for (i = 0, length = writerMimeTypes.length; i < length; i++) {
                                if (outputFormat.getMediaType().equals(writerMimeTypes[i].toLowerCase())) {
                                    outputFormats.add(outputFormat);
                                }
                            }
                        }
                    }
                }
                formatsMap.put(sourceFormat, outputFormats);
            }
        }
        return formatsMap;
    }

    public Set<OutputFormat> getAvailableOutputFormats(SourceFormat sourceFormat) {
        Set<OutputFormat> formats = getFormats().get(sourceFormat);
        if (formats == null) {
            formats = new HashSet<OutputFormat>();
        }
        return formats;
    }

    public Dimension getSize(ImageInputStream inputStream,
                             SourceFormat sourceFormat) throws Exception {
        // get width & height (without reading the entire image into memory)
        Iterator<ImageReader> iter = ImageIO.
                getImageReadersBySuffix(sourceFormat.getPreferredExtension());
        if (iter.hasNext()) {
            ImageReader reader = iter.next();
            int width, height;
            try {
                reader.setInput(inputStream);
                width = reader.getWidth(reader.getMinIndex());
                height = reader.getHeight(reader.getMinIndex());
            } finally {
                reader.dispose();
            }
            return new Dimension(width, height);
        }
        return null;
    }

    public Set<ProcessorFeature> getSupportedFeatures(SourceFormat sourceFormat) {
        return SUPPORTED_FEATURES;
    }

    public Set<Quality> getSupportedQualities(SourceFormat sourceFormat) {
        return SUPPORTED_QUALITIES;
    }

    public Set<SourceFormat> getSupportedSourceFormats() {
        Set<SourceFormat> sourceFormats = new HashSet<SourceFormat>();
        HashMap<SourceFormat, Set<OutputFormat>> formats = getFormats();
        for (SourceFormat sourceFormat : formats.keySet()) {
            if (formats.get(sourceFormat) != null &&
                    formats.get(sourceFormat).size() > 0) {
                sourceFormats.add(sourceFormat);
            }
        }
        return sourceFormats;
    }

    public void process(Parameters params, SourceFormat sourceFormat,
                        ImageInputStream inputStream, OutputStream outputStream)
            throws Exception {
        RenderedOp image = loadRegion(inputStream, params.getRegion());
        image = scaleImage(image, params.getSize());
        image = rotateImage(image, params.getRotation());
        image = filterImage(image, params.getQuality());
        outputImage(image, params.getOutputFormat(), outputStream);
    }

    private RenderedOp loadRegion(ImageInputStream inputStream, Region region) {
        // Load the requested region via JAI, which supports tiling for some
        // formats, in contrast to ImageIO.read() which loads the entire image
        ParameterBlockJAI pbj = new ParameterBlockJAI("ImageRead");
        ImageLayout layout = new ImageLayout();
        layout.setTileWidth(JAI_TILE_SIZE);
        layout.setTileHeight(JAI_TILE_SIZE);
        RenderingHints hints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, layout);
        pbj.setParameter("Input", inputStream);
        RenderedOp op = JAI.create("ImageRead", pbj, hints);

        RenderedOp croppedImage;
        if (region.isFull()) {
            croppedImage = op;
        } else {
            // calculate the region x, y, and actual width/height
            float x, y, requestedWidth, requestedHeight, actualWidth, actualHeight;
            if (region.isPercent()) {
                x = (float) (region.getX() / 100.0) * op.getWidth();
                y = (float) (region.getY() / 100.0) * op.getHeight();
                requestedWidth = (float) (region.getWidth() / 100.0) *
                        op.getWidth();
                requestedHeight = (float) (region.getHeight() / 100.0) *
                        op.getHeight();
            } else {
                x = region.getX();
                y = region.getY();
                requestedWidth = region.getWidth();
                requestedHeight = region.getHeight();
            }
            actualWidth = (x + requestedWidth > op.getWidth()) ?
                    op.getWidth() - x : requestedWidth;
            actualHeight = (y + requestedHeight > op.getHeight()) ?
                    op.getHeight() - y : requestedHeight;

            ParameterBlock pb = new ParameterBlock();
            pb.addSource(op);
            pb.add(x);
            pb.add(y);
            pb.add(actualWidth);
            pb.add(actualHeight);
            croppedImage = JAI.create("crop", pb);
        }
        return croppedImage;
    }

    private RenderedOp scaleImage(RenderedOp inputImage, Size size) {
        RenderedOp scaledImage;
        if (size.getScaleMode() == Size.ScaleMode.FULL) {
            scaledImage = inputImage;
        } else {
            float xScale = 1.0f;
            float yScale = 1.0f;
            if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_WIDTH) {
                xScale = (float) size.getWidth() / inputImage.getWidth();
                yScale = xScale;
            } else if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_HEIGHT) {
                yScale = (float) size.getHeight() / inputImage.getHeight();
                xScale = yScale;
            } else if (size.getScaleMode() == Size.ScaleMode.NON_ASPECT_FILL) {
                xScale = (float) size.getWidth() / inputImage.getWidth();
                yScale = (float) size.getHeight() / inputImage.getHeight();
            } else if (size.getScaleMode() == Size.ScaleMode.ASPECT_FIT_INSIDE) {
                double hScale = (float) size.getWidth() / inputImage.getWidth();
                double vScale = (float) size.getHeight() / inputImage.getHeight();
                xScale = (float) (inputImage.getWidth() * Math.min(hScale, vScale));
                yScale = (float) (inputImage.getHeight() * Math.min(hScale, vScale));
            } else if (size.getPercent() != null) {
                xScale = (float) (inputImage.getWidth() * (size.getPercent() / 100.0));
                yScale = xScale;
            }
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inputImage);
            pb.add(xScale);
            pb.add(yScale);
            pb.add(0.0f);
            pb.add(0.0f);
            pb.add(Interpolation.getInstance(Interpolation.INTERP_NEAREST)); // TODO: try bilinear/bicubic
            scaledImage = JAI.create("scale", pb);
        }
        return scaledImage;
    }

    private RenderedOp rotateImage(RenderedOp inputImage, Rotation rotation) {
        // do mirroring
        RenderedOp mirroredImage = inputImage;
        if (rotation.shouldMirror()) {
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(inputImage);
            pb.add(TransposeDescriptor.FLIP_HORIZONTAL);
            mirroredImage = JAI.create("transpose", pb);
        }
        // do rotation
        RenderedOp rotatedImage = mirroredImage;
        if (rotation.getDegrees() > 0) {
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(rotatedImage);
            pb.add(mirroredImage.getWidth() / 2);
            pb.add(mirroredImage.getHeight() / 2);
            pb.add(Math.toRadians(rotation.getDegrees()));
            pb.add(Interpolation.getInstance(Interpolation.INTERP_NEAREST)); // TODO: try bilinear/bicubic
            rotatedImage = JAI.create("rotate", pb);
        }
        return rotatedImage;
    }

    private RenderedOp filterImage(RenderedOp inputImage, Quality quality) {
        RenderedOp filteredImage = inputImage;
        /* TODO: write this
        if (quality != Quality.COLOR && quality != Quality.DEFAULT) {
            switch (quality) {
                case GRAY:
                    filteredImage = new BufferedImage(inputImage.getWidth(),
                            inputImage.getHeight(),
                            BufferedImage.TYPE_BYTE_GRAY);
                    break;
                case BITONAL:
                    filteredImage = new BufferedImage(inputImage.getWidth(),
                            inputImage.getHeight(),
                            BufferedImage.TYPE_BYTE_BINARY);
                    break;
            }
            Graphics2D g2d = filteredImage.createGraphics();
            g2d.drawImage(inputImage, 0, 0, null);
        }*/
        return filteredImage;
    }

    private void outputImage(RenderedOp image, OutputFormat format,
                             OutputStream outputStream) throws IOException {
        switch (format) {
            case GIF:
                break;
            case JP2:
                break;
            case JPG:
                JPEGEncodeParam param = new JPEGEncodeParam();
                ImageEncoder encoder = ImageCodec.createImageEncoder("JPEG",
                        outputStream, param);
                encoder.encode(image);
                break;
            case PDF:
                break;
            case PNG:
                PNGEncodeParam pngParam = new PNGEncodeParam.RGB();
                ImageEncoder pngEncoder = ImageCodec.createImageEncoder("PNG",
                        outputStream, pngParam);
                pngEncoder.encode(image);
                break;
            case TIF:
                TIFFImageEncoder tiffEnc = new TIFFImageEncoder(outputStream,
                        null);
                tiffEnc.encode(image);
                break;
            case WEBP:
                break;
        }

    }

}