import com.akane.akastudio.core.color.api.*
import com.akane.akastudio.core.color.grade.*
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.export.process.*
import kotlin.math.*

private fun image(w: Int, h: Int, pixel: (Int, Int) -> FloatArray): HalfFloatImage {
    val data = ShortArray(w*h*4)
    for (y in 0 until h) for (x in 0 until w) {
        val p = pixel(x,y)
        for (c in 0..3) data[(y*w+x)*4+c] = Half.fromFloat(if(c==3) 1f else p[c])
    }
    return HalfFloatImage(w,h,data)
}
private fun rgb(img: HalfFloatImage, x: Int, y: Int) = FloatArray(3) { Half.toFloat(img.rgba[(y*img.width+x)*4+it]) }
private fun y(p: FloatArray) = ColorMath.relativeLuminance(p[0],p[1],p[2])
private fun vec(p: FloatArray) = p.joinToString(",") { "%.8f".format(java.util.Locale.ROOT,it) }
private fun scalar(v: Float) = "%.8f".format(java.util.Locale.ROOT,v)
private fun encoded(img: HalfFloatImage, x: Int, y: Int): FloatArray {
    val row = IntArray(img.width)
    Dither.toSrgb8Row(img,y,row)
    return FloatArray(3) { c -> ColorMath.srgbToLinear(((row[x] shr (16-8*c)) and 255)/255f) }
}
private fun ratioAt(g: GainMapResult, x: Int, y: Int): Float {
    val code = g.gain[(y/4)*g.gainWidth+x/4].toInt() and 255
    return 2f.pow(log2(g.ratioMin)+(code/255f)*(log2(g.ratioMax)-log2(g.ratioMin)))
}
private fun probe(name: String, img: HalfFloatImage, x: Int=0, yy: Int=0) {
    val g=GainMapMath.compute(img)
    val h=rgb(img,x,yy); val b=rgb(g.sdr,x,yy); val enc=encoded(g.sdr,x,yy)
    val q=ratioAt(g,x,yy)
    val recon=FloatArray(3) {(enc[it]+g.epsilonSdr)*q-g.epsilonHdr}
    val social=encoded(img,x,yy)
    println(listOf("pixel",name,vec(h),vec(b),vec(enc),vec(social),vec(recon),scalar(q),scalar(g.ratioMax),scalar(log2(g.ratioMax)),scalar(y(b)),scalar(y(social)),scalar(y(recon))).joinToString("\t"))
    // Counterfactual luminance-only algorithm, preserving all other current settings/order.
    var peak=1f
    for(py in 0 until img.height) for(px in 0 until img.width) peak=max(peak,max(0f,y(rgb(img,px,py))))
    val legacy=image(img.width,img.height) {px,py ->
        val v=rgb(img,px,py); val lum=max(0f,y(v)); val f=(1f+lum/(peak*peak))/(1f+lum)
        FloatArray(3) {v[it]*f}
    }
    val hb=rgb(legacy,x,yy); val le=encoded(legacy,x,yy)
    var sumH=0.0;var sumB=0.0;var n=0
    for(py in (yy/4)*4 until min(img.height,(yy/4+1)*4)) for(px in (x/4)*4 until min(img.width,(x/4+1)*4)) {
        val lum=max(0f,y(rgb(img,px,py)));val f=(1f+lum/(peak*peak))/(1f+lum)
        sumH+=lum;sumB+=lum*f;n++
    }
    val lq=((sumH/n+g.epsilonSdr)/(sumB/n+g.epsilonSdr)).toFloat()
    val lr=FloatArray(3) {(le[it]+g.epsilonSdr)*lq-g.epsilonHdr}
    println(listOf("counterfactual_luma_only",name,vec(hb),vec(le),vec(lr),scalar(lq),scalar(y(le))).joinToString("\t"))
    // Exact per-channel ratios against the clipped Social-like base before 8-bit/JPEG losses.
    val clipped=FloatArray(3) {h[it].coerceIn(0f,1f)}
    val perChannel=FloatArray(3) {(max(0f,h[it])+g.epsilonHdr)/(clipped[it]+g.epsilonSdr)}
    println(listOf("option_RGB_gain",name,vec(clipped),vec(perChannel)).joinToString("\t"))
}
// The exact current JVM fixture, uncompressed and at its native resolution.
private fun photo(): LinearImage {
    val w=270; val h=480; val rgba=FloatArray(w*h*4)
    val sky=intArrayOf(0xFFFFFF,0x6FA8DC)
    val patches=intArrayOf(0xE53935,0xFDD835,0x00ACC1,0x43A047,0xFFFFFF)
    for (yy in 0 until h) for(x in 0 until w) {
        val o=(yy*w+x)*4
        for(c in 0..2) {
            val shift=16-8*c
            val code=when {
                yy<h/3 -> { val t=yy/(h/3f); ((sky[0] shr shift and 255)*(1-t)+(sky[1] shr shift and 255)*t)/255f }
                yy<2*h/3 -> (patches[x*patches.size/w] shr shift and 255)/255f
                else -> x/(w-1f)
            }
            rgba[o+c]=ColorMath.srgbToLinear(code)
        }
        rgba[o+3]=1f
    }
    return LinearImage(w,h,rgba)
}
private fun halves(img: LinearImage, scale: Int=1)=image(img.width*scale,img.height*scale) {x,yy ->
    FloatArray(3) {img.rgba[((yy/scale)*img.width+x/scale)*4+it]}
}
private fun stats(name: String, img: HalfFloatImage) {
    val g=GainMapMath.compute(img)
    var low=Float.POSITIVE_INFINITY; var high=Float.NEGATIVE_INFINITY; var negative=0
    for(i in img.rgba.indices) if(i%4!=3) {val v=Half.toFloat(img.rgba[i]);low=min(low,v);high=max(high,v);if(v<0)negative++}
    println(listOf("stats",name,"${img.width}x${img.height}",scalar(low),scalar(high),negative.toString(),scalar(g.ratioMax),scalar(log2(g.ratioMax)),if(g.ratioMax>=2f.pow(1f/3f))"ULTRA_HDR" else "SOCIAL").joinToString("\t"))
}
private fun cellError(name: String,img: HalfFloatImage) {
    val g=GainMapMath.compute(img)
    var worst=0f; var worstX=0;var worstY=0;var expected=0f;var actual=0f;var positive=0f
    var worstRepresentable=0f
    for(cy in 0 until g.gainHeight) for(cx in 0 until g.gainWidth) {
        var hdr=0f;var base=0f;var nonnegative=0f;var n=0
        for(yy in cy*4 until min(img.height,(cy+1)*4)) for(x in cx*4 until min(img.width,(cx+1)*4)) {
            val h=rgb(img,x,yy); val b=rgb(g.sdr,x,yy)
            hdr+=max(0f,y(h));base+=y(FloatArray(3) {b[it].coerceIn(0f,1f)});nonnegative+=y(FloatArray(3) {max(0f,h[it])});n++
        }
        hdr/=n;base/=n;nonnegative/=n
        val recon=(base+g.epsilonSdr)*ratioAt(g,cx*4,cy*4)-g.epsilonHdr
        val error=abs(recon-hdr)/max(0.0001f,hdr)
        worstRepresentable=max(worstRepresentable,abs(recon-nonnegative)/max(0.0001f,nonnegative))
        if(error>=worst) {worst=error;worstX=cx;worstY=cy;expected=hdr;actual=recon;positive=nonnegative}
    }
    println(listOf("cell_clamp_error",name,"$worstX,$worstY",scalar(worst),scalar(expected),scalar(actual),scalar(positive),scalar(worstRepresentable)).joinToString("\t"))
}
fun main() {
    println("pixel\tname\tHDR_RGB\tbase_half_RGB\tbase_8bit_linear_RGB\tSocial_8bit_linear_RGB\treconstructed_RGB\tsampled_ratio\tratioMax\tstops\tY_base_half\tY_Social_8bit\tY_recon")
    val red=image(16,8) {x,_ -> if(x<8)floatArrayOf(4f,0f,0f) else floatArrayOf(4f,4f,4f)}
    probe("AUD03_red4_beside_white4",red)
    probe("AUD03_white4",red,12)
    val cyan=image(16,8) {x,_ -> if(x<8)floatArrayOf(0f,1f,1f) else floatArrayOf(1f,1f,1f)}
    probe("AUD03_boosted_cyan",GainMapMath.boostHighlights(cyan))
    probe("M1_1.3_0.5_0.2",image(8,8) {_,_ ->floatArrayOf(1.3f,0.5f,0.2f)})
    probe("normal_SDR_0.4_0.3_0.2",image(8,8) {_,_ ->floatArrayOf(0.4f,0.3f,0.2f)})
    probe("L6_signed_0.2_neg0.05_2",image(8,8) {_,_ ->floatArrayOf(0.2f,-0.05f,2f)})
    println("stats\tname\tdimensions\tmin_channel\tmax_channel\tnegative_channels\tratioMax\tstops\tdecision_unboosted")
    val p=photo()
    stats("JVM_fixture_ungraded_native",halves(p))
    for(c in listOf(0f,0.5f,1f)) {
        val graded=CpuGradingPipeline.grade(p,GradeParams(look=MasterLook.MEDITERRANEAN_AZURE,contrast=c))
        stats("JVM_AZURE_contrast${c}_native",halves(graded))
        val expanded=halves(graded,4)
        stats("JVM_AZURE_contrast${c}_expanded4",expanded)
        if(c>0f) stats("JVM_AZURE_contrast${c}_expanded4_sharpened",LumaSharpen.apply(expanded))
    }
    val sharp=LumaSharpen.apply(red)
    stats("AUD03_red_white_sharpened",sharp)
    cellError("AUD03_red_white_sharpened",sharp)
    val nonnegative=image(sharp.width,sharp.height) {x,yy -> val v=rgb(sharp,x,yy); FloatArray(3) {max(0f,v[it])}}
    cellError("AUD03_red_white_sharpened_clamped_HDR",nonnegative)
    val signed=image(8,8) {_,_ ->floatArrayOf(0.2f,-0.05f,2f)}
    cellError("L6_signed_synthetic",signed)
    cellError("L6_signed_synthetic_clamped_HDR",image(8,8) {_,_ ->floatArrayOf(0.2f,0f,2f)})
    for(x in 5..10) probe("AUD03_sharpen_edge_x${x}",sharp,x,4)
}
