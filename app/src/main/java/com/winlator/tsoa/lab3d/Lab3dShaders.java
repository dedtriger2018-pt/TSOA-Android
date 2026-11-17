package com.winlator.tsoa.lab3d;

/** GLES 3.0 sources, exposed so the experimental renderer can be compiled off-device. */
public final class Lab3dShaders {
    private Lab3dShaders() {}
    private static String source(String... lines) { return String.join("\n", lines) + "\n"; }

    public static String fullscreenVertex() { return source(
        "#version 300 es",
        "precision highp float;",
        "out vec2 vUv;",
        "void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2)); vUv=p; gl_Position=vec4(p*2.0-1.0,0.0,1.0); }"
    ); }

    public static String sceneVertex() { return source(
        "#version 300 es",
        "precision highp float;",
        "layout(location=0) in vec3 aPosition; layout(location=1) in vec3 aNormal;",
        "layout(location=2) in mat4 iModel; layout(location=6) in vec4 iColorMetal; layout(location=7) in vec4 iSurface;",
        "uniform mat4 uViewProjection; uniform float uTime; uniform vec4 uRipple;",
        "out vec3 vWorld; out vec3 vNormal; flat out vec4 vColorMetal; flat out vec4 vSurface;",
        "void main(){ vec4 p=iModel*vec4(aPosition,1.0);",
        "if(iSurface.z>0.5&&iSurface.z<1.5){ float d=distance(p.xz,uRipple.xy); float age=uTime-uRipple.z; float ring=exp(-pow((d-age*3.5)*1.7,2.0))*exp(-age*.9)*uRipple.w; p.y+=sin(d*10.0-age*15.0)*ring*.035; }",
        "vec3 sq=vec3(dot(iModel[0].xyz,iModel[0].xyz),dot(iModel[1].xyz,iModel[1].xyz),dot(iModel[2].xyz,iModel[2].xyz));",
        "vWorld=p.xyz; vNormal=normalize(mat3(iModel)*(aNormal/max(sq,vec3(.0001)))); vColorMetal=iColorMetal; vSurface=iSurface; gl_Position=uViewProjection*p; }"
    ); }

    private static String atmosphere() { return source(
        "float hash21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}",
        "float noise2(vec2 p){ vec2 i=floor(p),f=fract(p); f=f*f*(3.0-2.0*f); return mix(mix(hash21(i),hash21(i+vec2(1,0)),f.x),mix(hash21(i+vec2(0,1)),hash21(i+1.0),f.x),f.y); }",
        "float fbm(vec2 p){return .54*noise2(p)+.28*noise2(p*2.03)+.12*noise2(p*4.07);}",
        "vec3 skyColor(vec3 ray,float time,float flash){",
        "float h=clamp(ray.y*.8+.18,0.0,1.0); vec3 c=mix(vec3(.075,.13,.16),vec3(.026,.052,.085),h);",
        "vec2 cloud=ray.xz/max(.15,ray.y+.28)*1.25+vec2(time*.009,time*.004); float n=fbm(cloud);",
        "float opening=exp(-dot((ray.xz-vec2(.08,-.88))*vec2(1.4,2.0),(ray.xz-vec2(.08,-.88))*vec2(1.4,2.0))*5.0)*smoothstep(.02,.42,ray.y);",
        "c+=vec3(.20,.27,.31)*opening*pow(1.0-n,2.0)*2.8; c=mix(c,c*.43,smoothstep(.32,.80,n)*smoothstep(-.04,.25,ray.y));",
        "c+=flash*vec3(.38,.52,.63)*(0.3+opening); return c; }"
    ); }

    public static String sceneFragment() { return source(
        "#version 300 es",
        "precision highp float;",
        "in vec3 vWorld; in vec3 vNormal; flat in vec4 vColorMetal; flat in vec4 vSurface;",
        "uniform vec3 uCamera; uniform float uTime; uniform float uFlash; uniform bool uPbr; uniform bool uLights; uniform vec4 uRipple;",
        "uniform vec3 uLightPos[3]; uniform vec3 uLightColor[3]; out vec4 fragColor;",
        atmosphere(),
        "const float PI=3.14159265359;",
        "vec3 fresnel(float c,vec3 f0){return f0+(1.0-f0)*pow(clamp(1.0-c,0.0,1.0),5.0);}",
        "float distribution(float ndh,float rough){float a=rough*rough;float a2=a*a;float d=ndh*ndh*(a2-1.0)+1.0;return a2/max(PI*d*d,.00001);}",
        "float smithPart(float nd,float rough){float k=(rough+1.0);k=k*k*.125;return nd/max(nd*(1.0-k)+k,.0001);}",
        "void main(){",
        "vec3 N=normalize(vNormal); vec3 albedo=vColorMetal.rgb; float metal=vColorMetal.a; float rough=vSurface.x; float emissive=vSurface.y;",
        "bool floorSurface=vSurface.z>.5&&vSurface.z<1.5; float puddle=0.0;",
        "if(floorSurface){",
        "vec2 p=vWorld.xz; float patches=fbm(p*.19+vec2(4.1,9.2)); puddle=smoothstep(.34,.57,patches);",
        "vec2 cell=abs(fract(p*vec2(.62,.38))-.5); float grout=1.0-smoothstep(.475,.493,max(cell.x,cell.y));",
        "albedo=mix(vec3(.030,.045,.052),vec3(.072,.082,.084),noise2(floor(p*vec2(.62,.38)))); albedo*=mix(.38,1.0,grout); albedo*=mix(1.0,.48,puddle);",
        "rough=mix(.63,.105,puddle); metal=.08;",
        "float fine=noise2(p*19.0); vec2 rip=vec2(sin(p.y*25.0+uTime*2.8),cos(p.x*24.0-uTime*2.1))*.009;",
        "float d=distance(p,uRipple.xy); float age=uTime-uRipple.z; float ring=exp(-pow((d-age*3.5)*1.7,2.0))*exp(-age*.9)*uRipple.w;",
        "rip+=normalize(p-uRipple.xy+vec2(.001))*cos(d*10.0-age*15.0)*ring*.12;",
        "N=normalize(N+vec3((fine-.5)*.055+rip.x,0.0,(noise2(p*19.0+13.0)-.5)*.055+rip.y)*mix(.6,1.0,puddle));",
        "}else if(emissive<.1){float grain=noise2(vWorld.xz*28.0+vWorld.y*4.0); rough=clamp(rough+(grain-.5)*.08,.08,1.0); albedo*=.9+.2*grain;}",
        "vec3 V=normalize(uCamera-vWorld); float ndv=max(dot(N,V),.001); vec3 f0=mix(vec3(.04),albedo,metal); vec3 color=vec3(0.0);",
        "for(int i=0;i<3;i++){vec3 delta=uLightPos[i]-vWorld;float d2=dot(delta,delta);vec3 L=normalize(delta);vec3 H=normalize(V+L);float ndl=max(dot(N,L),0.0);",
        "vec3 radiance=uLightColor[i]/(1.0+d2*.12); if(!uLights)radiance*=.0;",
        "if(uPbr){vec3 F=fresnel(max(dot(H,V),0.0),f0);float D=distribution(max(dot(N,H),0.0),rough);float G=smithPart(ndv,rough)*smithPart(ndl,rough);vec3 spec=D*G*F/max(4.0*ndv*ndl,.001);color+=((1.0-F)*(1.0-metal)*albedo/PI+spec)*radiance*ndl;}",
        "else { color+=albedo*radiance*ndl*.35; }}",
        "vec3 R=reflect(-V,N); vec3 env=skyColor(R,uTime,uFlash); vec3 F=fresnel(ndv,f0);",
        "vec3 ambient=albedo*(vec3(.12,.19,.23)+max(N.y,0.0)*vec3(.20,.24,.26))*(1.0-metal);",
        "if(uPbr){ color+=ambient+env*F*(1.0-rough*.55)*1.9; }else{color+=ambient*1.6;}",
        "color+=albedo*emissive; color+=albedo*uFlash*max(dot(N,normalize(vec3(-.3,1.,-.2))),0.0)*.7;",
        "float dist=length(uCamera-vWorld);float fog=1.0-exp(-dist*.015-exp(-max(vWorld.y,0.0)*1.15)*dist*.009);vec3 fogColor=vec3(.065,.115,.145)+uFlash*vec3(.18,.24,.3);color=mix(color,fogColor,clamp(fog,0.,.92));",
        "fragColor=vec4(max(color,vec3(0.0)),1.0); }"
    ); }

    public static String skyFragment() { return source(
        "#version 300 es", "precision highp float;", "in vec2 vUv; uniform mat4 uInverseViewProjection; uniform vec3 uCamera; uniform float uTime; uniform float uFlash; out vec4 fragColor;",
        atmosphere(),
        "void main(){vec4 p=uInverseViewProjection*vec4(vUv*2.0-1.0,1.0,1.0);vec3 ray=normalize(p.xyz/p.w-uCamera);fragColor=vec4(skyColor(ray,uTime,uFlash),1.0);}"
    ); }

    public static String rainVertex() { return source(
        "#version 300 es", "precision highp float;",
        "uniform mat4 uViewProjection; uniform vec3 uCamera; uniform float uTime; uniform float uRainIntensity; out vec2 vDrop; out float vAlpha;",
        "float hash(float p){return fract(sin(p*127.1)*43758.5453);}",
        "void main(){float id=float(gl_InstanceID); float h=hash(id+1.0);vec3 p=vec3((hash(id+2.0)-.5)*40.0,mod(hash(id+3.0)*18.0-uTime*(10.0+h*7.0),18.0),uCamera.z-hash(id+4.0)*46.0);",
        "p.x+=sin(uTime*.25)*.6+p.y*.12;float side=(gl_VertexID==0||gl_VertexID==2)?-.5:.5;float top=gl_VertexID<2?1.0:0.0;float width=.009+.01*h; p+=vec3(side*width,(top-.5)*(.24+.35*h),0.0);",
        "vDrop=vec2(side*2.0,top);vAlpha=(.11+.12*h)*uRainIntensity;gl_Position=uViewProjection*vec4(p,1.0);}"
    ); }

    public static String rainFragment() { return source(
        "#version 300 es", "precision highp float;", "in vec2 vDrop; in float vAlpha; uniform float uFlash; out vec4 fragColor;",
        "void main(){float a=(1.0-abs(vDrop.x))*sin(vDrop.y*3.14159)*vAlpha;fragColor=vec4(vec3(.34,.57,.67)+uFlash*.5,a);}"
    ); }

    public static String blurFragment() { return source(
        "#version 300 es", "precision highp float;", "in vec2 vUv; uniform sampler2D uColor; uniform vec2 uDirection; out vec4 fragColor;",
        "void main(){vec3 c=texture(uColor,vUv).rgb*.227027;c+=texture(uColor,vUv+uDirection*1.384615).rgb*.316216;c+=texture(uColor,vUv-uDirection*1.384615).rgb*.316216;c+=texture(uColor,vUv+uDirection*3.230769).rgb*.070270;c+=texture(uColor,vUv-uDirection*3.230769).rgb*.070270;fragColor=vec4(c,1.0);}"
    ); }

    public static String compositeFragment() { return source(
        "#version 300 es", "precision highp float;", "in vec2 vUv; uniform sampler2D uColor; uniform sampler2D uBlur; uniform sampler2D uDepth; uniform bool uDof; uniform bool uBloom; uniform float uFocus; uniform float uTime; uniform float uExposure; uniform float uBrightness; out vec4 fragColor;",
        "float linearDepth(float d){const float nearZ=.18;const float farZ=130.0;float z=d*2.0-1.0;return 2.0*nearZ*farZ/(farZ+nearZ-z*(farZ-nearZ));}",
        "vec3 aces(vec3 x){return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14),0.0,1.0);}",
        "void main(){vec3 sharp=texture(uColor,vUv).rgb;vec3 blur=texture(uBlur,vUv).rgb;float z=linearDepth(texture(uDepth,vUv).r);float coc=clamp(abs(z-uFocus)/max(z,.2)*1.15-.12,0.0,.85);",
        "vec3 c=mix(sharp,blur,uDof?coc:0.0);if(uBloom){vec3 b=max(blur-vec3(.52),vec3(0.0));c+=b*.24;}c=aces(c*1.45*uExposure);c=pow(c,vec3(1.0/2.2))*uBrightness;",
        "vec2 q=vUv-.5;c*=1.0-.30*dot(q,q)*1.8;float grain=fract(sin(dot(gl_FragCoord.xy+fract(uTime)*17.0,vec2(12.9898,78.233)))*43758.5453)-.5;c+=grain/255.0;fragColor=vec4(c,1.0);}"
    ); }
}
