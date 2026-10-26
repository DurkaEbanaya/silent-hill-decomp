/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Runs without a game disc: real SDL/OpenGL context plus packaged examples. */
#define SDL_MAIN_HANDLED
#include <SDL.h>
#include <PsyX/common/glad.h>
#include <stdio.h>
#include <string.h>
#include "pc_baked_preview.h"
#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_STDIO
#include "stb_image.h"

#ifdef NDEBUG
#undef NDEBUG
#endif
/* assert must be active in RelWithDebInfo too. */
#include <assert.h>

int main(int argc,char** argv)
{
    (void)argc;(void)argv;
    setvbuf(stdout,NULL,_IONBF,0);setvbuf(stderr,NULL,_IONBF,0);
    puts("Starting SDL/GL preview test");
    SDL_Window* window;
    SDL_GLContext context;
    PcBakedPreview image;
    GLuint pbo,oldTexture;
    GLint value;
    int i;
    SDL_SetMainReady();
    assert(SDL_Init(SDL_INIT_VIDEO | SDL_INIT_TIMER)==0);
    puts("SDL initialized");
    printf("SDL OpenGL library: %s\n",SDL_getenv("SDL_OPENGL_LIBRARY")?SDL_getenv("SDL_OPENGL_LIBRARY"):"system");
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,2);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_CORE);
    puts("Creating SDL window");
    window=SDL_CreateWindow("Baked preview validation",0,0,640,360,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN);
    if(!window){fprintf(stderr,"SDL window: %s\n",SDL_GetError());return 1;}
    puts("Creating GL context");
    context=SDL_GL_CreateContext(window);
    if(!context){fprintf(stderr,"SDL GL: %s\n",SDL_GetError());return 1;}
    puts("GL context created");
    int loaded=gladLoadGLLoader((GLADloadproc)SDL_GL_GetProcAddress);
    if(!loaded){fprintf(stderr,"GL loader failed: %s\n",SDL_GetError());return 1;}
    if(!glGetString || !glGenBuffers || !glGenTextures || !glGetIntegerv || !glPixelStorei) {
        fprintf(stderr,"Required GL entry point missing (string=%d buffers=%d textures=%d integers=%d pixelstore=%d)\n",
            glGetString!=NULL,glGenBuffers!=NULL,glGenTextures!=NULL,glGetIntegerv!=NULL,glPixelStorei!=NULL);return 1;
    }
    printf("GL backend: %s\n",glGetString(GL_VERSION));
    puts("Testing texture uploads and FOV values");
    glGenBuffers(1,&pbo);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,pbo);
    glBufferData(GL_PIXEL_UNPACK_BUFFER,16,NULL,GL_STATIC_DRAW);
    glGenTextures(1,&oldTexture);glBindTexture(GL_TEXTURE_2D,oldTexture);
    glPixelStorei(GL_UNPACK_ALIGNMENT,8);glPixelStorei(GL_UNPACK_ROW_LENGTH,17);
    glPixelStorei(GL_UNPACK_SKIP_ROWS,2);glPixelStorei(GL_UNPACK_SKIP_PIXELS,3);
    for(i=40;i<=140;i++) {
        if(i%20==0)printf("FOV %d\n",i);
        const char* keys[]={"fps_fov","tps_fov","ots_fov"};int k;
        for(k=0;k<3;k++) {
            assert(Pc_BakedPreview_Get(keys[k],(float)i,&image));
            assert(image.value==(float)i && !image.approximate && !image.animated);
            assert(image.width==640 && image.height==360 && glIsTexture(image.texture));
        }
    }
    glGetIntegerv(GL_TEXTURE_BINDING_2D,&value);assert(value==(GLint)oldTexture);
    glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING,&value);assert(value==(GLint)pbo);
    glGetIntegerv(GL_UNPACK_ALIGNMENT,&value);assert(value==8);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH,&value);assert(value==17);
    glGetIntegerv(GL_UNPACK_SKIP_ROWS,&value);assert(value==2);
    glGetIntegerv(GL_UNPACK_SKIP_PIXELS,&value);assert(value==3);
    puts("Testing weather loops");
    for(i=30;i<=60;i+=30) {
        assert(Pc_BakedPreview_Get("weather_sim_hz",(float)i,&image));
        assert(image.animated && image.width==480 && image.height==270 && !image.approximate);
        SDL_Delay(40);
        assert(Pc_BakedPreview_Get("weather_sim_hz",(float)i,&image));
    }
    assert(Pc_BakedPreview_Get("fps_fov",71.1f,&image) && image.approximate && image.value==71);
    assert(!Pc_BakedPreview_Get("not_a_setting",0,&image));
    assert(!Pc_BakedPreview_Get(NULL,0,&image));
    assert(glGetError()==GL_NO_ERROR);
    Pc_BakedPreview_Close();
    assert(Pc_BakedPreview_Get("use_pgxp",1,&image) && image.animated);
    Pc_BakedPreview_Close();
    glDeleteTextures(1,&oldTexture);glDeleteBuffers(1,&pbo);
    SDL_GL_DeleteContext(context);SDL_DestroyWindow(window);SDL_Quit();
    puts("PASS: 303 FOV values, both weather loops, nearest/missing, GL state, close/reopen");
    return 0;
}
