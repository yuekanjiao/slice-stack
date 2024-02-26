/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author Yuekan Jiao
 */
import ij.*;
import ij.process.*;
import ij.gui.*;
import ij.measure.Calibration;
import java.awt.*;
import ij.plugin.*;
//import ij.plugin.frame.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Point2D;
import java.awt.image.ColorModel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Vector;

public class Slice_Stack implements PlugIn,
        MouseMotionListener, MouseListener,
        DialogListener,
        ImageListener {

    ImagePlus imp;
    int imageType;
    ImageStack stack;
    ImageCanvas canvas;
    int imageWidth;
    int imageHeight;
    int stackSize;

    int redZ; // Note redZ and blueZ start from 0 instead of 1
    int blueZ;
    double lineWidth;
    double cursorJumpDist;
    int cursorOnLine = 1; // red line = 1, blue line = 2
    int cursorAtEnd = 0; // 1 and 2 are two ends, 0 is between two ends
    double redX1, redY1;
    double redX2, redY2;
    double blueX1, blueY1;
    double blueX2, blueY2;
    double angle; // in the image axes, i.e. x goes right and y goes down
    double dash;

    NonBlockingGenericDialog dialog;

    /* Both slicedWidth and slicedHeigh are number of pixels 
       based on pixelWidth/pixelHeight of the stack:
       slicedWidth = width of rotated image to red/blue line
       slicedHeight = distance between red and blue line
     */
    int slicedCanvasWidth;
    int slicedCanvasHeight;
    int slicedCanvasX;
    int slicedCanvasY;
    int slicedWidth;
    int slicedHeight;
    Point2D.Double[][] slicedXY;
    double[] slicedZ;
    ImagePlus impSliced;
    ImageProcessor ipSliced;

    @Override
    public void run(String arg) {
        imp = IJ.getImage();
        if (imp.getNChannels() > 1) {
            IJ.showMessage("Single Channel", "Slice Stack requires an image of single channel");
            return;
        }
        imageType = imp.getType();
        if ((imageType != ImagePlus.GRAY8)
                && (imageType != ImagePlus.GRAY16)
                && (imageType != ImagePlus.GRAY32)
                && (imageType != ImagePlus.COLOR_RGB)) {
            IJ.showMessage("Image Type", "Slice Stack requires an image of:\n"
                    + "8-bit\n"
                    + "16-bit\n"
                    + "32-bit\n"
                    + "RGB Color");
            return;
        }
        imageWidth = imp.getWidth();
        imageHeight = imp.getHeight();
        stackSize = imp.getImageStackSize();
        stack = imp.getImageStack();

        initLines();
        drawLines();
        initCanvas();
        getSlicedXYZ();
        initSliced();
        getSliced();
        showSliced();
        zoomExact(impSliced, imp.getCanvas().getMagnification());

        ImageWindow win = imp.getWindow();
        canvas = win.getCanvas();
        canvas.removeMouseListener(this);
        canvas.removeMouseMotionListener(this);
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);

        ImagePlus.removeImageListener(this);
        ImagePlus.addImageListener(this);

        int tool = Toolbar.getInstance().getToolId("Slice Tool");
        if (tool < 0) {
            new MacroInstaller().install("macro 'Slice Tool-Cf00L03f2C00fL0cfb'{}");
        } else {
            Toolbar.getInstance().setTool(tool);
        }

        showDialog();
    }

    public void initCanvas() {
        // get Canvas width
        double canvasWidth = Math.sqrt(Math.pow(imageWidth, 2) + Math.pow(imageHeight, 2));
        slicedCanvasWidth = (int) Math.ceil(canvasWidth / 2.0) * 2;

        // get canvas height
        Calibration cal = imp.getCalibration();
        double pixelWidth = cal.pixelWidth;
        double pixelDepth = cal.pixelDepth; // or voxel depth

        // Usually pixeldepth > pixelwidth, will interpolate data between stack slices
        double depthRatio = pixelDepth / pixelWidth;

        double canvasHeight = Math.sqrt(Math.pow(canvasWidth, 2) + Math.pow(stackSize * depthRatio, 2));
        slicedCanvasHeight = (int) Math.ceil(canvasHeight / 2.0) * 2;
    }

    public void initSliced() {
        impSliced = new ImagePlus();
        ImageProcessor ipSlice;
        ColorModel colorModel;

        switch (imageType) {
            case ImagePlus.GRAY8:
                ipSliced = new ByteProcessor(slicedCanvasWidth, slicedCanvasHeight);
                ipSlice = stack.getProcessor(1);
                colorModel = ipSlice.getColorModel();
                ipSliced.setMinAndMax(ipSlice.getMin(), ipSlice.getMax());
                ipSliced.setColorModel(colorModel);
                getSlicedInteger();
                break;
            case ImagePlus.COLOR_256:
                break;
            case ImagePlus.GRAY16:
                ipSliced = new ShortProcessor(slicedCanvasWidth, slicedCanvasHeight);
                ipSlice = stack.getProcessor(1);
                colorModel = ipSlice.getColorModel();
                ipSliced.setMinAndMax(ipSlice.getMin(), ipSlice.getMax());
                ipSliced.setColorModel(colorModel);
                getSlicedInteger();
                break;
            case ImagePlus.GRAY32:
                ipSliced = new FloatProcessor(slicedCanvasWidth, slicedCanvasHeight);
                ipSlice = stack.getProcessor(1);
                colorModel = ipSlice.getColorModel();
                ipSliced.setMinAndMax(ipSlice.getMin(), ipSlice.getMax());
                ipSliced.setColorModel(colorModel);
                getSlicedFloat();
                break;
            case ImagePlus.COLOR_RGB:
                ipSliced = new ColorProcessor(slicedCanvasWidth, slicedCanvasHeight);
                getSlicedRGB();
                break;
        }

    }

    public void initLines() {
        redZ = 0;
        blueZ = stackSize - 1;

        if (imageWidth < imageHeight) {
            lineWidth = imageWidth * 0.01;
            cursorJumpDist = imageWidth * 0.01;
            dash = imageWidth * 0.05;
        } else {
            lineWidth = imageHeight * 0.01;
            cursorJumpDist = imageHeight * 0.01;
            dash = imageWidth * 0.05;
        }
        if (lineWidth < 1) {
            lineWidth = 1;
        }
        if (cursorJumpDist < 1) {
            cursorJumpDist = 1;
        }
        if (dash < 1) {
            dash = 1;
        }

        /* init: 
           1.(redX1, redY1), (redX2, redY2) 
           2. (blueX1, blueY1), (blueX2, blueY2)
           3. angle  
         */
        redX1 = 0;
        redY1 = (imageHeight - 1) / 4.0;
        redX2 = imageWidth - 1;
        redY2 = redY1 - (imageHeight - 1) / 16.0;
        blueX1 = 0;
        blueY1 = (imageHeight - 1) * 3.0 / 4.0;
        blueX2 = imageWidth - 1;
        blueY2 = blueY1 - (imageHeight - 1) / 16.0;
        angle = Math.atan2(redY2 - redY1, redX2 - redX1);
        angle = angle / Math.PI * 180.0;

    }

    public ArrayList<Point2D.Double> getIntercepts(double x0, double y0) {
        double x, y;
        Point2D.Double point;
        ArrayList<Point2D.Double> pointList = new ArrayList<>();

        if (angle == 0) {
            x = 0;
            y = y0;
            point = new Point2D.Double(x, y);
            pointList.add(point);
            x = imageWidth - 1.0;
            y = y0;
            point = new Point2D.Double(x, y);
            pointList.add(point);
        } else if ((angle == 90) || (angle == -90)) {
            x = x0;
            y = 0;
            point = new Point2D.Double(x, y);
            pointList.add(point);
            x = x0;
            y = imageHeight - 1;
            point = new Point2D.Double(x, y);
            pointList.add(point);
        } else {
            double arc = angle / 180.0 * Math.PI;
            double k = Math.tan(arc);
            /* line y = k*(x - x0) + y0 re-written as ax + by + c = 0 
               where a = k, b = -1; c = -k*x0 + y0;
               intercepts to 4 axis;
               top axis:  y = 0 
               left axis: x = 0
               bottom axis: y - height + 1 = 0
               right axis: x - width + 1 = 0
             */
            point = getIntersect(k, -1, -k * x0 + y0, 0, 1, 0); // top axis
            /* give point a tolerance of 0.01 beyond the image because of Java's
               double/float, for example 255.0 in double might be 255.000009  
             */
            if ((point.x > -0.01) && (point.x < (imageWidth - 0.99))) {
                pointList.add(point);
            }
            point = getIntersect(k, -1, -k * x0 + y0, 0, 1, -imageHeight + 1); // bottom axis
            if ((point.x > -0.01) && (point.x < (imageWidth - 0.99))) {
                pointList.add(point);
            }
            point = getIntersect(k, -1, -k * x0 + y0, 1, 0, 0); // left axis
            if ((point.y > -0.01) && (point.y < (imageHeight - 0.99))) {
                pointList.add(point);
            }
            point = getIntersect(k, -1, -k * x0 + y0, 1, 0, -imageWidth + 1); // right axis
            if ((point.y > -0.01) && (point.y < (imageHeight - 0.99))) {
                pointList.add(point);
            }

            /* Process the cases like one end of the line is at top left corner, 
            the other end at bottom right corner/bottom/right of the image
             */
            int listSize = pointList.size();
            Point2D.Double jPoint, iPoint;
            if (listSize > 2) {
                for (int j = listSize - 1; j > 0; j--) {
                    jPoint = pointList.get(j);
                    for (int i = j - 1; i > - 1; i--) {
                        iPoint = pointList.get(i);
                        if ((Math.round(iPoint.x) == Math.round(jPoint.x))
                                && (Math.round(iPoint.y) == Math.round(jPoint.y))) {
                            pointList.remove(j);
                        }

                    }
                }
            }
        }
        return pointList;

    }

    public double getPerpDist(double x0, double y0, double x, double y) {
        /* if a line passes through a point (x0, y0) with an angle θ, 
        the distance from point(x, y) to the line is
        abs(cos(θ)(y - y0) - sin(x - x0))
         */
        double arc = angle / 180.0 * Math.PI;
        double dist = Math.abs(Math.cos(arc) * (y0 - y) - Math.sin(arc) * (x0 - x));
        return dist;
    }

    public Point2D.Double getIntersect(double a1, double b1, double c1, double a2, double b2, double c2) {
        /* two lines:
        a1*x + b1*y + c1 = 0;
        a2*x + b2*y + c2 = 0;
        the point(x, y) of intersection:
        x = (b1*c2 - b2*c1)/(a1*b2 - a2*b1)
        y = (a2*c1 - a1*c2)/(a1*b2 - a2*b1)
         */
        double x = (b1 * c2 - b2 * c1) / (a1 * b2 - a2 * b1);
        double y = (a2 * c1 - a1 * c2) / (a1 * b2 - a2 * b1);
        Point2D.Double pointDouble = new Point2D.Double(x, y);
        return pointDouble;
    }

    public void drawLines() {

        Overlay overlay = new Overlay();
        // draw the red and blue lines
        Roi redLine;
        Roi blueLine;
        Roi dashRoi;
        double dashRate = 0.6; // dash over pitch
        double pitch = dash / dashRate;
        double lineLength;
        double dx;
        double dy;
        int nPitch;
        double residue;
        int currentSlice = imp.getCurrentSlice() - 1;
        if (redZ == currentSlice) {
            redLine = new Line(redX1, redY1, redX2, redY2);
            redLine.setStrokeWidth(lineWidth);
            redLine.setStrokeColor(Color.red);
            overlay.add(redLine);
        } else {
            lineLength = Math.sqrt(Math.pow(redX2 - redX1, 2) + Math.pow(redY2 - redY1, 2));
            dx = pitch / lineLength * (redX2 - redX1);
            dy = pitch / lineLength * (redY2 - redY1);
            nPitch = (int) (lineLength / pitch);
            residue = lineLength % pitch;
            for (int i = 0; i < nPitch; i++) {
                dashRoi = new Line(redX1 + i * dx, redY1 + i * dy, redX1 + (i + dashRate) * dx, redY1 + (i + dashRate) * dy);
                dashRoi.setStrokeWidth(lineWidth);
                dashRoi.setStrokeColor(Color.red);
                overlay.add(dashRoi);
            }
            if (residue > 0) {
                if (residue < pitch) {
                    dashRoi = new Line(redX1 + nPitch * dx, redY1 + nPitch * dy, redX2, redY2);
                    dashRoi.setStrokeWidth(lineWidth);
                    dashRoi.setStrokeColor(Color.red);
                    overlay.add(dashRoi);
                } else {
                    dashRoi = new Line(redX1 + nPitch * dx, redY1 + nPitch * dy, redX1 + (nPitch + 1) * dx, redY1 + (nPitch + 1) * dy);
                    dashRoi.setStrokeWidth(lineWidth);
                    dashRoi.setStrokeColor(Color.red);
                    overlay.add(dashRoi);
                }
            }
        }

        if (blueZ == currentSlice) {
            blueLine = new Line(blueX1, blueY1, blueX2, blueY2);
            blueLine.setStrokeWidth(lineWidth);
            blueLine.setStrokeColor(Color.blue);
            overlay.add(blueLine);
        } else {
            lineLength = Math.sqrt(Math.pow(blueX2 - blueX1, 2) + Math.pow(blueY2 - blueY1, 2));
            dx = pitch / lineLength * (blueX2 - blueX1);
            dy = pitch / lineLength * (blueY2 - blueY1);
            nPitch = (int) (lineLength / pitch);
            residue = lineLength % pitch;
            for (int i = 0; i < nPitch; i++) {
                dashRoi = new Line(blueX1 + i * dx, blueY1 + i * dy, blueX1 + (i + dashRate) * dx, blueY1 + (i + dashRate) * dy);
                dashRoi.setStrokeWidth(lineWidth);
                dashRoi.setStrokeColor(Color.blue);
                overlay.add(dashRoi);
            }
            if (residue > 0) {
                if (residue < pitch) {
                    dashRoi = new Line(blueX1 + nPitch * dx, blueY1 + nPitch * dy, blueX2, blueY2);
                    dashRoi.setStrokeWidth(lineWidth);
                    dashRoi.setStrokeColor(Color.blue);
                    overlay.add(dashRoi);
                } else {
                    dashRoi = new Line(blueX1 + nPitch * dx, blueY1 + nPitch * dy, blueX1 + (nPitch + 1) * dx, blueY1 + (nPitch + 1) * dy);
                    dashRoi.setStrokeWidth(lineWidth);
                    dashRoi.setStrokeColor(Color.blue);
                    overlay.add(dashRoi);
                }
            }
        }
        imp.setOverlay(overlay);
    }

    public void showDialog() {
        dialog = new NonBlockingGenericDialog("Slice Stack");
        dialog.addSlider("Red Z", 1, stackSize, redZ + 1);
        dialog.addSlider("Blue Z", 1, stackSize, blueZ + 1);
        //dialog.setInsets(50, 0, 0);
        dialog.addButton("Help", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showHelp();
            }
        });
        Vector vect = dialog.getSliders();
        Scrollbar redScrollbar = (Scrollbar) vect.get(0);
        redScrollbar.setBackground(Color.RED);
        Scrollbar blueScrollbar = (Scrollbar) vect.get(1);
        blueScrollbar.setBackground(Color.blue);
        dialog.addDialogListener(this);
        dialog.showDialog();
        canvas.removeMouseListener(this);
        canvas.removeMouseMotionListener(this);
        imp.setOverlay(null);
        ImagePlus.removeImageListener(this);
    }

    @Override
    public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
        /* 1. "OK" button, invoked but e = null, 
           2. "Cancel" button: not invoked
           3. dialog "X" button: not invoked
           4. dialog.dispose(): invoked but e = null 
         */
        if (e != null) {
            Vector vect = gd.getSliders();
            redZ = (int) ((Scrollbar) vect.get(0)).getValue() - 1;
            blueZ = (int) ((Scrollbar) vect.get(1)).getValue() - 1;
            drawLines();
            getSlicedXYZ();
            getSliced();
            showSliced();
        }
        return true;
    }

    public void showHelp() {

        Dialog helpDialog = new Dialog(new Frame(), "Slice Stack Help", true);
        helpDialog.setLayout(new BorderLayout());
        TextArea text = new TextArea(35, 70);
        text.setEditable(false);
        text.setText("Plugin Slice Stack uses 2 parallel lines red and blue on 2 different slices \n"
                + "of a stack or the same slice to get a sliced image, the top and bottom \n"
                + "of the sliced image correspond to the red and blue lines on the stack. \n"
                + "The plugin runs on 3D stacks of 8, 16, 32 bits and RGB color.\n"
                + "\n"
                + "The red and blue lines can: \n"
                + "- Move up and down the stack in Z by the 2 sliders red and blue \n"
                + "in the Slice Stack window. The line shows as a solid line when it is \n"
                + "on the current slice, otherwise the line shows as a dash line. \n"
                + "By default the red line is on the top slice of the stack \n"
                + "and the blue line is on the bottom slice.\n"
                + "- Rotate in the XY plane using the mouse to drag the 4 ends of the 2 lines.\n"
                + "- Translate in the XY plane using the mouse to drag between the 2 ends \n"
                + "of the line.\n"
                + "\n"
                + "Therefore the 2 lines have in total of 6 transformations in the XY plane. \n"
                + "In case the mouse is pressed but not on any of the 2 lines, \n"
                + "the previous transformation will continue. For example, if the red line \n"
                + "was translated previously, the red line will continue translate \n"
                + "to where the mouse is pressed. To switch among the 6 transformations, \n"
                + "simply click on the line at the end or between its 2 ends. \n"
                + "Select the Slice Tool installed in the ImageJ Toolbar by the plugin \n"
                + "to do the 6 transformations of the 2 lines.  \n"
                + "\n"
                + "The plugin assumes the stack has equal pixel width and pixel height, \n"
                + "and larger voxel depth as in usual microscopy images. A pixel \n"
                + "in the sliced image is bilinear interpolated from the slice of the stack \n"
                + "if both the red and blue lines are on the slice. If a pixel \n"
                + "in the sliced image is between 2 slices, it is linear interpolated \n"
                + "between the 2 bilinear interpolated points in the 2 slices.");
        helpDialog.add(text, "Center");
        Button okButton = new Button("OK");
        okButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent ae) {
                if (ae.getActionCommand().equals("OK")) {
                    helpDialog.dispose();
                }
            }
        });

        Panel okPanel = new Panel();
        okPanel.add(okButton);
        helpDialog.add(okPanel, "South");
        helpDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                helpDialog.dispose();
            }
        });

        helpDialog.pack();
        helpDialog.setVisible(true);

    }

    public void grabLine(int offscreenX, int offscreenY) {
        /* 1. grab red line or blue line and 
           2. determine where the mosue grabs on the line:
              one end or between two ends
         */
        double redX = (redX1 + redX2) / 2.0;
        double redY = (redY1 + redY2) / 2.0;
        double blueX = (blueX1 + blueX2) / 2.0;
        double blueY = (blueY1 + blueY2) / 2.0;
        double redDist = getPerpDist(redX, redY, offscreenX, offscreenY);
        double blueDist = getPerpDist(blueX, blueY, offscreenX, offscreenY);

        double lineLength;
        double dist;
        if (redDist < cursorJumpDist) { // red line
            cursorOnLine = 1;
            cursorAtEnd = 0;
            lineLength = Math.sqrt(Math.pow(redX2 - redX1, 2) + Math.pow(redY2 - redY1, 2));
            // only rotate the line when linelength > 3 * cursorJumpDist
            if (lineLength > 3 * cursorJumpDist) {
                dist = Math.sqrt(Math.pow(offscreenX - redX1, 2) + Math.pow(offscreenY - redY1, 2));
                if (dist < cursorJumpDist) {
                    cursorAtEnd = 1; // 
                } else {
                    dist = Math.sqrt(Math.pow(offscreenX - redX2, 2) + Math.pow(offscreenY - redY2, 2));
                    if (dist < cursorJumpDist) {
                        cursorAtEnd = 2;
                    }
                }
            }
        } else if (blueDist < cursorJumpDist) { // blue line
            cursorOnLine = 2;
            cursorAtEnd = 0;
            lineLength = Math.sqrt(Math.pow(blueX2 - blueX1, 2) + Math.pow(blueY2 - blueY1, 2));
            // only rotate the line when linegenth > 3 * cursorJumpDist
            if (lineLength > 3 * cursorJumpDist) {
                dist = Math.sqrt(Math.pow(offscreenX - blueX1, 2) + Math.pow(offscreenY - blueY1, 2));
                if (dist < cursorJumpDist) {
                    cursorAtEnd = 1;
                } else {
                    dist = Math.sqrt(Math.pow(offscreenX - blueX2, 2) + Math.pow(offscreenY - blueY2, 2));
                    if (dist < cursorJumpDist) {
                        cursorAtEnd = 2;
                    }
                }
            }
        }
    }

    public void getLines(int offscreenX, int offscreenY) {
        /* get:
           1. (redX1, redY1), (redX2, redY2)
           2. (blueX1, blueY1), (blueX2, blueY2)
           3. angle  
         */
        ArrayList<Point2D.Double> pointList = null; // updated end points of red/blue line
        Point2D.Double point1, point2;
        double redX, redY;
        double blueX, blueY;
        double lineLength;

        switch (cursorOnLine) {
            case 1: // red line
                switch (cursorAtEnd) {
                    case 0: // translate the line 
                        pointList = getIntercepts(offscreenX, offscreenY);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        redX = (point1.x + point2.x) / 2.0;
                        redY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        redX1 = redX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY1 = redY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        redX2 = redX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY2 = redY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        break;

                    case 1: // drag end 1 to rotate the lines
                        angle = Math.atan2(redY2 - offscreenY, redX2 - offscreenX) / Math.PI * 180.0;

                        pointList = getIntercepts(offscreenX, offscreenY);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        redX = (point1.x + point2.x) / 2.0;
                        redY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        redX1 = redX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY1 = redY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        redX2 = redX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY2 = redY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);

                        pointList = getIntercepts(blueX2, blueY2);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        blueX = (point1.x + point2.x) / 2.0;
                        blueY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        blueX1 = blueX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY1 = blueY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        blueX2 = blueX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY2 = blueY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        break;

                    case 2: // drag end 2 to rotate the lines
                        angle = Math.atan2(offscreenY - redY1, offscreenX - redX1) / Math.PI * 180.0;

                        pointList = getIntercepts(offscreenX, offscreenY);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        redX = (point1.x + point2.x) / 2.0;
                        redY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        redX1 = redX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY1 = redY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        redX2 = redX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY2 = redY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);

                        pointList = getIntercepts(blueX1, blueY1);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        blueX = (point1.x + point2.x) / 2.0;
                        blueY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        blueX1 = blueX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY1 = blueY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        blueX2 = blueX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY2 = blueY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        break;
                }
                break;

            case 2: // blue line

                switch (cursorAtEnd) {

                    case 0: // Move the line
                        pointList = getIntercepts(offscreenX, offscreenY);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        blueX = (point1.x + point2.x) / 2.0;
                        blueY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        blueX1 = blueX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY1 = blueY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        blueX2 = blueX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY2 = blueY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        break;

                    case 1: // drag end 1 to rotate the lines
                        angle = Math.atan2(blueY2 - offscreenY, blueX2 - offscreenX) / Math.PI * 180.0;

                        pointList = getIntercepts(offscreenX, offscreenY);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        blueX = (point1.x + point2.x) / 2.0;
                        blueY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        blueX1 = blueX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY1 = blueY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        blueX2 = blueX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY2 = blueY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);

                        pointList = getIntercepts(redX2, redY2);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        redX = (point1.x + point2.x) / 2.0;
                        redY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        redX1 = redX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY1 = redY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        redX2 = redX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY2 = redY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        break;

                    case 2: // drag end 2 to rotate the lines
                        angle = Math.atan2(offscreenY - blueY1, offscreenX - blueX1) / Math.PI * 180.0;

                        pointList = getIntercepts(offscreenX, offscreenY);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        blueX = (point1.x + point2.x) / 2.0;
                        blueY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        blueX1 = blueX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY1 = blueY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        blueX2 = blueX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        blueY2 = blueY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);

                        pointList = getIntercepts(redX1, redY1);
                        point1 = pointList.get(0);
                        point2 = pointList.get(1);
                        redX = (point1.x + point2.x) / 2.0;
                        redY = (point1.y + point2.y) / 2.0;
                        lineLength = Math.sqrt(Math.pow(point2.x - point1.x, 2) + Math.pow(point2.y - point1.y, 2));
                        redX1 = redX - lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY1 = redY - lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        redX2 = redX + lineLength / 2.0 * Math.cos(angle / 180.0 * Math.PI);
                        redY2 = redY + lineLength / 2.0 * Math.sin(angle / 180.0 * Math.PI);
                        break;
                }
                break;
        }
    }

    public void getSlicedXYZ() {
        // get sliced width
        ImagePlus impTemp = NewImage.createByteImage("Rotate", imageWidth, imageHeight, 1, NewImage.FILL_BLACK);
        IJ.run(impTemp, "Select All", "");
        IJ.run(impTemp, "Rotate...", "angle=" + angle);
        Roi roi = impTemp.getRoi();
        Rectangle rect = roi.getBounds();
        // the number of columns for the sliced image
        slicedWidth = rect.width;

        double redX = (redX1 + redX2) / 2;
        double redY = (redY1 + redY2) / 2;
        double blueX = (blueX1 + blueX2) / 2;
        double blueY = (blueY1 + blueY2) / 2;
        double centerX = (imageWidth - 1) / 2.0;
        double centerY = (imageHeight - 1) / 2.0;

        /* Get the perp intersections from the image center to the red and blue lines
           line: y = k*(x - x0) + y0 or
           k*x - y + (y0 - k*x0) = 0; 
           the perp line from the image center (xc, yc) has a slope of -1/k
           y = -1/k*(x - xc) + yc or
           1/k*x + y - (yc + 1/k*xc) = 0
           in case k = 0 :
           blue line: y - y0 = 0
           perp line: x - xc = 0;
           in case k = infinity:
           blue line: x - x0 = 0; 
           perp line: y - yc = 0;
         */
        // redMid and blueMid are the perp intersections to red and blue lines 
        // from the image center. 
        Point2D.Double redMid;
        Point2D.Double blueMid;
        if (angle == 0) {
            redMid = new Point2D.Double();
            blueMid = new Point2D.Double();
            redMid.x = centerX;
            redMid.y = redY;
            blueMid.x = centerX;
            blueMid.y = blueY;
        } else if ((angle == 90) || (angle == -90)) {
            redMid = new Point2D.Double();
            blueMid = new Point2D.Double();
            redMid.x = redX;
            redMid.y = centerY;
            blueMid.x = blueX;
            blueMid.y = centerY;
        } else {
            double k = Math.tan(angle / 180.0 * Math.PI);
            redMid = getIntersect(k, -1, (redY - k * redX),
                    1 / k, 1, -(centerY + 1 / k * centerX));
            blueMid = getIntersect(k, -1, (blueY - k * blueX),
                    1 / k, 1, -(centerY + 1 / k * centerX));
        }

        // Calculate the horitzontal dist of red and blue lines
        double horizonDist = getPerpDist(redX, redY, blueMid.x, blueMid.y);

        // get sliced height
        Calibration cal = imp.getCalibration();
        double pixelWidth = cal.pixelWidth;
        double pixelDepth = cal.pixelDepth; // or voxel depth

        // Usually pixeldepth > pixelwidth, will interpolate data between stack slices
        double depthRatio = pixelDepth / pixelWidth;

        // Get sliced height:
        // slicedDist: the distance between the red and blue lines 
        // slicedHeight: the number of rows for the sliced image
        double slicedDist = Math.sqrt(Math.pow(horizonDist, 2) + Math.pow((blueZ - redZ) * depthRatio, 2));
        slicedHeight = (int) slicedDist + 1;

        double bottomMidX = redMid.x + (blueMid.x - redMid.x) * (slicedHeight - 1.0) / slicedDist;
        double bottomMidY = redMid.y + (blueMid.y - redMid.y) * (slicedHeight - 1.0) / slicedDist;

        slicedXY = new Point2D.Double[slicedHeight][slicedWidth];
        double sineVal = Math.sin(angle / 180 * Math.PI);
        double cosineVal = Math.cos(angle / 180 * Math.PI);
        double topLeftX = -(slicedWidth - 1) / 2.0 * cosineVal + redMid.x;
        double topLeftY = -(slicedWidth - 1) / 2.0 * sineVal + redMid.y;
        double topRightX = (slicedWidth - 1) / 2.0 * cosineVal + redMid.x;
        double topRightY = (slicedWidth - 1) / 2.0 * sineVal + redMid.y;
        double bottomLeftX = -(slicedWidth - 1) / 2.0 * cosineVal + bottomMidX;
        double bottomLeftY = -(slicedWidth - 1) / 2.0 * sineVal + bottomMidY;
        double bottomRightX = (slicedWidth - 1) / 2.0 * cosineVal + bottomMidX;
        double bottomRightY = (slicedWidth - 1) / 2.0 * sineVal + bottomMidY;
        // interpolate the sliced image pixels between red and blue lines
        Point2D.Double point;
        for (int i = 0; i < slicedWidth; i++) {
            point = new Point2D.Double();
            point.x = topLeftX + (topRightX - topLeftX) / (slicedWidth - 1) * i;
            point.y = topLeftY + (topRightY - topLeftY) / (slicedWidth - 1) * i;
            slicedXY[0][i] = point;
            point = new Point2D.Double();
            point.x = bottomLeftX + (bottomRightX - bottomLeftX) / (slicedWidth - 1) * i;
            point.y = bottomLeftY + (bottomRightY - bottomLeftY) / (slicedWidth - 1) * i;
            slicedXY[slicedHeight - 1][i] = point;
        }

        for (int i = 0; i < slicedWidth; i++) {
            for (int j = 1; j < (slicedHeight - 1); j++) {
                point = new Point2D.Double();
                point.x = slicedXY[0][i].x + (slicedXY[slicedHeight - 1][i].x - slicedXY[0][i].x) / (slicedHeight - 1) * j;
                point.y = slicedXY[0][i].y + (slicedXY[slicedHeight - 1][i].y - slicedXY[0][i].y) / (slicedHeight - 1) * j;
                slicedXY[j][i] = point;
            }
        }

        // slicedZ is the corresponding stack z coordiantes (voxels) 
        // of the rows in the sliced imaage   
        slicedZ = new double[slicedHeight];
        int m;
        if (slicedDist > 0) {
            for (m = 0; m < slicedHeight; m++) {
                slicedZ[m] = (redZ + m / slicedDist * (blueZ - redZ));
            }
        } else {
            slicedZ[0] = redZ;
        }

        // The top left of the sliced in the canvas
        slicedCanvasX = (int) Math.ceil((slicedCanvasWidth - 1) / 2.0 - (slicedWidth - 1) / 2.0);
        slicedCanvasY = (int) Math.ceil((slicedCanvasHeight - 1) / 2.0 - (slicedHeight - 1) / 2.0);

    }

    public void getSliced() {
        ipSliced.setColor(Color.BLACK);
        ipSliced.fill();
        switch (imageType) {
            case ImagePlus.GRAY8:
                getSlicedInteger();
                break;
            case ImagePlus.GRAY16:
                getSlicedInteger();
                break;
            case ImagePlus.GRAY32:
                getSlicedFloat();
                break;
            case ImagePlus.COLOR_RGB:
                getSlicedRGB();
                break;
        }
    }

    public void getSlicedInteger() {

        ImageProcessor ipRedderSlice = null;
        ImageProcessor ipBluerSlice = null;
        int redderPixelValue;
        int bluerPixelValue;
        int pixelValue;
        int sliceIndex;
        int m;

        if (redZ == blueZ) {
            ipRedderSlice = stack.getProcessor(redZ + 1);
            for (m = 0; m < slicedHeight; m++) {
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (int) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        } else if (redZ < blueZ) {
            for (m = 0; m < (slicedHeight - 1); m++) {
                sliceIndex = (int) slicedZ[m];
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex + 2);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (int) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (int) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (int) (redderPixelValue * ((sliceIndex + 1) - slicedZ[m])
                            + bluerPixelValue * (slicedZ[m] - sliceIndex));
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }

            m = slicedHeight - 1;
            sliceIndex = (int) slicedZ[m];
            if (slicedZ[m] > sliceIndex) {  // slicedHeight - 1 is above blueZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex + 2);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (int) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (int) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (int) (redderPixelValue * ((sliceIndex + 1) - slicedZ[m])
                            + bluerPixelValue * (slicedZ[m] - sliceIndex));
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);

                }
            } else { // slicedHeight - 1 falls on blueZ
                ipBluerSlice = stack.getProcessor(sliceIndex + 1);
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (int) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        } else if (redZ > blueZ) {
            for (m = 0; m < (slicedHeight - 1); m++) {
                sliceIndex = (int) Math.ceil(slicedZ[m]);
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (int) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (int) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (int) (redderPixelValue * (slicedZ[m] - (sliceIndex - 1))
                            + bluerPixelValue * (sliceIndex - slicedZ[m]));
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
            m = slicedHeight - 1;
            sliceIndex = (int) Math.ceil(slicedZ[m]);
            if (slicedZ[m] < sliceIndex) {  // slicedHeight - 1 is above redZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (int) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (int) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (int) (redderPixelValue * (slicedZ[m] - (sliceIndex - 1))
                            + bluerPixelValue * (sliceIndex - slicedZ[m]));
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            } else {  // slicedHeight - 1 falls on redZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (int) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        }
    }

    public void getSlicedFloat() {

        ImageProcessor ipRedderSlice = null;
        ImageProcessor ipBluerSlice = null;
        float redderPixelValue;
        float bluerPixelValue;
        float pixelValue;
        int sliceIndex;
        int m;

        if (redZ == blueZ) {
            ipRedderSlice = stack.getProcessor(redZ + 1);
            for (m = 0; m < slicedHeight; m++) {
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (float) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.setf(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        } else if (redZ < blueZ) {
            for (m = 0; m < (slicedHeight - 1); m++) {
                sliceIndex = (int) slicedZ[m];
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex + 2);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (float) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (float) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (float) (redderPixelValue * ((sliceIndex + 1) - slicedZ[m])
                            + bluerPixelValue * (slicedZ[m] - sliceIndex));
                    ipSliced.setf(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }

            m = slicedHeight - 1;
            sliceIndex = (int) slicedZ[m];
            if (slicedZ[m] > sliceIndex) {  // slicedHeight - 1 is above blueZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex + 2);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (float) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (float) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (float) (redderPixelValue * ((sliceIndex + 1) - slicedZ[m])
                            + bluerPixelValue * (slicedZ[m] - sliceIndex));
                    ipSliced.setf(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            } else { // slicedHeight - 1 falls on blueZ
                ipBluerSlice = stack.getProcessor(sliceIndex + 1);
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (float) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.setf(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        } else if (redZ > blueZ) {
            for (m = 0; m < (slicedHeight - 1); m++) {
                sliceIndex = (int) Math.ceil(slicedZ[m]);
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (float) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (float) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (float) (redderPixelValue * (slicedZ[m] - (sliceIndex - 1))
                            + bluerPixelValue * (sliceIndex - slicedZ[m]));
                    ipSliced.setf(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
            m = slicedHeight - 1;
            sliceIndex = (int) Math.ceil(slicedZ[m]);
            if (slicedZ[m] < sliceIndex) {  // slicedHeight - 1 is above redZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (float) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (float) ipBluerSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (float) (redderPixelValue * (slicedZ[m] - (sliceIndex - 1))
                            + bluerPixelValue * (sliceIndex - slicedZ[m]));
                    ipSliced.setf(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            } else {  // slicedHeight - 1 falls on redZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (int) ipRedderSlice.getInterpolatedValue(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.setf(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        }
    }

    public void getSlicedRGB() {

        ImageProcessor ipRedderSlice = null;
        ImageProcessor ipBluerSlice = null;
        int redderPixelValue;
        int bluerPixelValue;
        int pixelValue;
        int sliceIndex;
        int m;

        if (redZ == blueZ) {
            ipRedderSlice = stack.getProcessor(redZ + 1);
            ipRedderSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
            for (m = 0; m < slicedHeight; m++) {
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (int) ipRedderSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        } else if (redZ < blueZ) {
            for (m = 0; m < (slicedHeight - 1); m++) {
                sliceIndex = (int) slicedZ[m];
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex + 2);
                ipRedderSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                ipBluerSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (int) ipRedderSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (int) ipBluerSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = zLinearRGB1(redderPixelValue, bluerPixelValue, slicedZ[m]);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }

            m = slicedHeight - 1;
            sliceIndex = (int) slicedZ[m];
            if (slicedZ[m] > sliceIndex) {  // slicedHeight - 1 is above blueZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex + 2);
                ipRedderSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                ipBluerSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (int) ipRedderSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (int) ipBluerSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = zLinearRGB1(redderPixelValue, bluerPixelValue, slicedZ[m]);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            } else { // slicedHeight - 1 falls on blueZ
                ipBluerSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (int) ipBluerSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        } else if (redZ > blueZ) {
            for (m = 0; m < (slicedHeight - 1); m++) {
                sliceIndex = (int) Math.ceil(slicedZ[m]);
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex);
                ipRedderSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                ipBluerSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (int) ipRedderSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (int) ipBluerSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (int) zLinearRGB2(redderPixelValue, bluerPixelValue, slicedZ[m]);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
            m = slicedHeight - 1;
            sliceIndex = (int) Math.ceil(slicedZ[m]);
            if (slicedZ[m] < sliceIndex) {  // slicedHeight - 1 is above redZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipBluerSlice = stack.getProcessor(sliceIndex);
                ipRedderSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                ipBluerSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                for (int i = 0; i < slicedWidth; i++) {
                    redderPixelValue = (int) ipRedderSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    bluerPixelValue = (int) ipBluerSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    pixelValue = (int) zLinearRGB2(redderPixelValue, bluerPixelValue, slicedZ[m]);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            } else {  // slicedHeight - 1 falls on redZ
                ipRedderSlice = stack.getProcessor(sliceIndex + 1);
                ipRedderSlice.setInterpolationMethod(ImageProcessor.BILINEAR);
                for (int i = 0; i < slicedWidth; i++) {
                    pixelValue = (int) ipRedderSlice.getPixelInterpolated(slicedXY[m][i].x, slicedXY[m][i].y);
                    ipSliced.set(slicedCanvasX + i, slicedCanvasY + m, pixelValue);
                }
            }
        }
    }

    public int zLinearRGB1(int redderValue, int bluerValue, double z) {
        double redderRed = (int) (redderValue & 0xff0000) >> 16;
        double redderGreen = (int) (redderValue & 0x00ff00) >> 8;
        double redderBlue = (int) (redderValue & 0x0000ff);
        double bluerRed = (int) (bluerValue & 0xff0000) >> 16;
        double bluerGreen = (int) (bluerValue & 0x00ff00) >> 8;
        double bluerBlue = (int) (bluerValue & 0x0000ff);
        int zInt = (int) z;
        int red = (int) (redderRed * ((zInt + 1) - z) + bluerRed * (z - zInt));
        int green = (int) (redderGreen * ((zInt + 1) - z) + bluerGreen * (z - zInt));
        int blue = (int) (redderBlue * ((zInt + 1) - z) + bluerBlue * (z - zInt));
        int value = (red << 16) + (green << 8) + blue;
        return value;
    }

    public int zLinearRGB2(int redderValue, int bluerValue, double z) {
        double redderRed = (int) (redderValue & 0xff0000) >> 16;
        double redderGreen = (int) (redderValue & 0x00ff00) >> 8;
        double redderBlue = (int) (redderValue & 0x0000ff);
        double bluerRed = (int) (bluerValue & 0xff0000) >> 16;
        double bluerGreen = (int) (bluerValue & 0x00ff00) >> 8;
        double bluerBlue = (int) (bluerValue & 0x0000ff);
        int zInt = (int) Math.ceil(z);
        int red = (int) (redderRed * (z - (zInt - 1)) + bluerRed * (zInt - z));
        int green = (int) (redderGreen * (z - (zInt - 1)) + bluerGreen * (zInt - z));
        int blue = (int) (redderBlue * (z - (zInt - 1)) + bluerBlue * (zInt - z));
        int value = (red << 16) + (green << 8) + blue;
        return value;
    }

    public void showSliced() {
        if (!impSliced.isVisible()) {
            impSliced = new ImagePlus("Sliced", ipSliced);
            impSliced.show();
        } else {
            impSliced.updateAndDraw();
        }
        //zoomExact(impSliced, imp.getCanvas().getMagnification());
    }

    public void zoomExact(ImagePlus img, double mag) {
        ImageWindow win = img.getWindow();
        if (win == null) {
            return;
        }
        ImageCanvas c = win.getCanvas();
        if (c == null) {
            return;
        }
        c.setMagnification(mag);
        // see if it fits
        double w = img.getWidth() * mag;
        double h = img.getHeight() * mag;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        if (w > screen.width - 10) {
            w = screen.width - 10;
        }
        if (h > screen.height - 30) {
            h = screen.height - 30;
        }
        try {
            Field f_srcRect = c.getClass().getDeclaredField("srcRect");
            f_srcRect.setAccessible(true);
            f_srcRect.set(c, new Rectangle(0, 0, (int) (w / mag), (int) (h / mag)));
            c.setDrawingSize((int) w, (int) h);
            win.pack();
            c.repaint();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (!(Toolbar.getToolName().equals("Slice Tool"))) {
            return;
        }
        int x = e.getX();
        int y = e.getY();
        int offscreenX = canvas.offScreenX(x);
        int offscreenY = canvas.offScreenY(y);
        if (offscreenX < 0) {
            offscreenX = 0;
        }
        if (offscreenX > (imageWidth - 1)) {
            offscreenX = imageWidth - 1;
        }
        if (offscreenY < 0) {
            offscreenY = 0;
        }
        if (offscreenY > (imageHeight - 1)) {
            offscreenY = imageHeight - 1;
        }
        getLines(offscreenX, offscreenY);
        drawLines();
        //getSlicedXYZ();
        //getSliced();
        //showSliced();
    }

    @Override
    public void mouseMoved(MouseEvent e) {

    }

    @Override
    public void mouseClicked(MouseEvent e) {

    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (!(Toolbar.getToolName().equals("Slice Tool"))) {
            return;
        }
        int x = e.getX();
        int y = e.getY();
        int offscreenX = canvas.offScreenX(x);
        int offscreenY = canvas.offScreenY(y);
        if (offscreenX < 0) {
            offscreenX = 0;
        }
        if (offscreenX > (imageWidth - 1)) {
            offscreenX = imageWidth - 1;
        }
        if (offscreenY < 0) {
            offscreenY = 0;
        }
        if (offscreenY > (imageHeight - 1)) {
            offscreenY = imageHeight - 1;
        }
        grabLine(offscreenX, offscreenY);
        getLines(offscreenX, offscreenY);
        drawLines();
        //getSlicedXYZ();
        //getSliced();
        //showSliced();
    }

    @Override
    public void mouseReleased(MouseEvent e) {

        if (!(Toolbar.getToolName().equals("Slice Tool"))) {
            return;
        }
        int x = e.getX();
        int y = e.getY();
        int offscreenX = canvas.offScreenX(x);
        int offscreenY = canvas.offScreenY(y);
        if (offscreenX < 0) {
            offscreenX = 0;
        }
        if (offscreenX > (imageWidth - 1)) {
            offscreenX = imageWidth - 1;
        }
        if (offscreenY < 0) {
            offscreenY = 0;
        }
        if (offscreenY > (imageHeight - 1)) {
            offscreenY = imageHeight - 1;
        }
        getLines(offscreenX, offscreenY);
        drawLines();
        getSlicedXYZ();
        getSliced();
        showSliced();
    }

    @Override
    public void mouseEntered(MouseEvent e) {

    }

    @Override
    public void mouseExited(MouseEvent e) {

    }

    @Override
    public void imageOpened(ImagePlus imp) {

    }

    @Override
    public void imageClosed(ImagePlus imp) {
        if (imp.equals(this.imp)) {
            dialog.dispose();
        }
    }

    @Override
    public void imageUpdated(ImagePlus imp) {
        if (imp.equals(this.imp)) {
            drawLines();
        }
    }
}
