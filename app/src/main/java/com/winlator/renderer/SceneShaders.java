package com.winlator.renderer;

/** Profile coordinates start at the upper left, while compositor textures use GL coordinates. */
public final class SceneShaders {
    private SceneShaders() {}

    private static final String COMMON = String.join("\n",
            "precision highp float;",
            "uniform sampler2D screenTexture, referenceTexture;",
            "uniform vec2 viewOrigin, viewExtent;",
            "in vec2 vUV;",
            "layout(location=0) out vec4 outFragColor;",
            "vec2 screenUV(vec2 p) {",
            "    vec2 halfTexel=0.5/vec2(textureSize(screenTexture,0));",
            "    return clamp(viewOrigin+viewExtent*vec2(p.x,1.0-p.y),viewOrigin+halfTexel,viewOrigin+viewExtent-halfTexel);",
            "}",
            "float difference(vec3 a, vec3 b) { vec3 d=abs(a-b); return max(d.r,max(d.g,d.b)); }");

    /** Runs into one RGBA8 texel. Reject global cuts and low-information flat references. */
    public static String getRecognitionFragmentShader() {
        return String.join("\n", COMMON,
                "void main() {",
                "    float votes=0.0; vec3 sum=vec3(0), squares=vec3(0);",
                "    for(int j=0;j<4;++j) for(int i=0;i<6;++i) {",
                "        vec2 p=(vec2(i,j)+vec2(0.5))/vec2(6,4);",
                "        vec3 ref=texture(referenceTexture,p).rgb;",
                "        vec3 live=texture(screenTexture,screenUV(p)).rgb;",
                "        votes+=1.0-smoothstep(0.035,0.115,difference(ref,live));",
                "        sum+=ref; squares+=ref*ref;",
                "    }",
                "    vec3 mean=sum/24.0; float variance=dot(squares/24.0-mean*mean,vec3(0.333333));",
                "    float confidence=smoothstep(0.0005,0.0025,variance)*smoothstep(0.75,0.89,votes/24.0);",
                "    outFragColor=vec4(confidence,0,0,1);",
                "}");
    }

    public static String getReconstructionFragmentShader() {
        return String.join("\n", COMMON,
                "uniform sampler2D structTexture, scoreTexture;",
                "uniform vec2 levels, profileStep;",
                "uniform float phase;",
                "float matchAt(vec2 p) {",
                "    float d=difference(texture(screenTexture,screenUV(p)).rgb,texture(referenceTexture,p).rgb);",
                "    return 1.0-smoothstep(0.012,0.055,d);",
                "}",
                "void main() {",
                "    vec4 src=texture(screenTexture,vUV); outFragColor=src;",
                "    vec2 p=(vUV-viewOrigin)/viewExtent; p.y=1.0-p.y;",
                "    if(any(lessThan(p,vec2(0))) || any(greaterThan(p,vec2(1)))) return;",
                "    float score=texelFetch(scoreTexture,ivec2(0),0).r;",
                "    if(score<=0.0) return;",
                "    vec4 s=texture(structTexture,p);",
                "    if(s.a<=0.0) return;",
                "    float local=1.0-smoothstep(0.012,0.055,difference(src.rgb,texture(referenceTexture,p).rgb));",
                "    if(local<=0.0) return;",
                "    vec2 dx=vec2(profileStep.x,0), dy=vec2(0,profileStep.y);",
                "    vec4 west=texture(structTexture,p-dx), east=texture(structTexture,p+dx);",
                "    vec4 north=texture(structTexture,p-dy), south=texture(structTexture,p+dy);",
                "    float allow=min(s.a,min(min(west.a,east.a),min(north.a,south.a)));",
                "    if(allow<=0.0) return;",
                // Guard thin overlays and sprite boundaries in both axes. The author can also
                // exclude whole UI regions and characters, independently of these residual tests.
                "    local=min(local,min(matchAt(p-dx),matchAt(p+dx)));",
                "    local=min(local,min(matchAt(p-dy),matchAt(p+dy)));",
                "    float weight=score*local*allow;",
                "    if(weight<=0.0) return;",
                "    vec3 rgb=src.rgb;",
                "    if(levels.x>=1.0 && s.g>0.0) {",
                // Two bounded frequencies on the same periodic clock; no drift or RNG.
                "        vec2 wave=vec2(sin(phase + 5.0*p.y),cos(phase + 4.0*p.x));",
                "        if(levels.x>=2.0) wave+=0.22*vec2(sin(2.0*phase+11.0*p.y),cos(2.0*phase+9.0*p.x));",
                "        vec2 shift=wave*(0.28+0.72*s.r)*s.g*levels.x*vec2(0.0010,0.00075);",
                "        vec2 q=clamp(p+shift,vec2(0),vec2(1)); vec4 qs=texture(structTexture,q);",
                "        float safe=qs.a*qs.g*(1.0-smoothstep(0.035,0.12,abs(qs.r-s.r)));",
                "        safe*=min(matchAt(q),matchAt(0.5*(p+q)));",
                "        rgb=mix(rgb,texture(screenTexture,screenUV(q)).rgb,safe);",
                "    }",
                "    if(levels.y>=1.0) {",
                // Derivatives of *authored depth*, scaled in normalized image coordinates.
                // Discontinuities have unreliable normals and must not become bright rims.
                "        vec2 gradient=vec2(east.r-west.r,south.r-north.r);",
                "        float edge=1.0-smoothstep(0.06,0.24,max(abs(gradient.x),abs(gradient.y)));",
                "        vec3 n=normalize(vec3(-0.065*gradient/max(profileStep,vec2(0.00001)),1.0));",
                "        vec3 light=normalize(vec3(-0.55,-0.50,0.75));",
                "        if(levels.y>=2.0) light=normalize(light+vec3(0.10*sin(phase),0.065*cos(phase),0));",
                "        float diffuse=dot(n,light)-light.z;",
                "        float gain=0.065*levels.y*(diffuse+0.40*(s.r-0.45));",
                "        vec3 added=rgb*gain;",
                "        if(levels.y>=2.0) {",
                "            float spec=pow(max(0.0,dot(n,normalize(light+vec3(0,0,1)))),13.0);",
                "            float cavity=max(0.0,0.25*(north.r+south.r+east.r+west.r)-s.r);",
                "            added+=vec3(1.0,0.90,0.76)*0.048*s.b*spec - rgb*min(0.06,cavity*0.6);",
                "        }",
                "        if(levels.y>=3.0) {",
                "            vec3 bounce=texture(referenceTexture,clamp(p+3.0*dy,vec2(0),vec2(1))).rgb;",
                // A bounded height-field ray towards the virtual light, over 4 profile texels.
                // Only author-approved continuous depths can cast contact shadows.
                "            vec2 ray=normalize(light.xy)*profileStep;",
                "            float shadow=0.0;",
                "            for(int i=1;i<=4;++i) {",
                "                vec2 t=p+float(i)*ray;",
                "                if(any(lessThan(t,vec2(0))) || any(greaterThan(t,vec2(1)))) break;",
                "                vec4 sampleDepth=texture(structTexture,t);",
                "                float delta=sampleDepth.r-s.r;",
                "                if(sampleDepth.a<0.95 || abs(delta)>0.22) break;",
                "                shadow=max(shadow,smoothstep(0.006,0.055,delta-0.008*float(i)));",
                "            }",
                "            added+=0.036*bounce*rgb*(1.0-s.b)-0.045*shadow*rgb;",
                "        }",
                "        rgb+=edge*clamp(added,vec3(-0.095),vec3(0.095));",
                "    }",
                "    outFragColor=vec4(mix(src.rgb,clamp(rgb,0.0,1.0),weight),src.a);",
                "}");
    }
}
