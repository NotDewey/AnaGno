# Bubble Zoom regression checks

Run from PowerShell with a JDK 17 or 21 installation:

```powershell
./tools/bubble_checks/run.ps1 -JavaHome 'C:\Users\Dewey\.jdks\jbr-21.0.11'
```

These checks compile the production sizing and edge-filter functions using the
project's cached Kotlin compiler. No emulator or extra test dependencies are needed.
They cover readable-text sizing, font scaling, display-area limits, transparent
background preservation, opaque interiors, native antialiasing and thin tails.

They do not establish segmentation quality on real pages. For device validation,
revisit the pages in the September 7 recording after the V35.2 cache regenerates.
Check the black connected balloons, the white “I see my temper” balloon, the black
“I thought so” balloon and “…ADAM?”. Compare outlines, text size and covered art.
The edge filter only softens pixel steps; it cannot repair an incorrect contour.
