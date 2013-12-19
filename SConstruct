################################
# scons build system file
################################
#
# Most of this file can be used as a boilerplate build file.
# The only parts that are dependent on the details of the
# software package being compiled are the name of the tar file
# and the list of scons files to be run in the lower level
# directories.
# NOTE: to include other scons files, use the python command
#       execfile('fileName')
#
################################

# get operating system info
import re
import sys
import glob
import os, string, subprocess, SCons.Node.FS
from os import access, F_OK, sep, symlink, lstat
from subprocess import Popen, PIPE

os.umask(022)

# Software version
versionMajor = '14'
versionMinor = '0'

# determine the os and machine names
uname    = os.uname();
platform = uname[0]
machine  = uname[4]
osname   = os.getenv('CODA_OSNAME', platform + '-' +  machine)

# Create an environment while importing the user's PATH.
# This allows us to get to the vxworks compiler for example.
# So for vxworks, make sure the tools are in your PATH
env = Environment(ENV = {'PATH' : os.environ['PATH']})

def recursiveDirs(root) :
    return filter( (lambda a : a.rfind( ".svn")==-1 ),  [ a[0] for a in os.walk(root)]  )

def unique(list) :
    return dict.fromkeys(list).keys()

def scanFiles(dir, accept=["*.cpp"], reject=[]) :
    sources = []
    paths = recursiveDirs(dir)
    for path in paths :
        for pattern in accept :
            sources+=glob.glob(path+"/"+pattern)
    for pattern in reject :
        sources = filter( (lambda a : a.rfind(pattern)==-1 ),  sources )
    return unique(sources)

def subdirsContaining(root, patterns):
    dirs = unique(map(os.path.dirname, scanFiles(root, patterns)))
    dirs.sort()
    return dirs


####################################################################################
# Create a Builder to install symbolic links, where "source" is list of node objects
# of the existing files, and "target" is a list of node objects of link files.
# NOTE: link file must have same name as its source file.
# Currently, this routine is not used.
####################################################################################
def buildSymbolicLinks(target, source, env):    
    # For each file to create a link for ...
    for s in source:
        filename = os.path.basename(str(s))

        # is there a corresponding link to make?
        makeLink = False
        for t in target:
            linkname = os.path.basename(str(t))
            if not linkname == filename:
                continue
            else :
                makeLink = True
                break

        # go to next source since no corresponding link
        if not makeLink:
            continue
        
        # If link exists don't recreate it
        try:
            # Test if the symlink exists
            lstat(str(t))
        except OSError:
            # OK, symlink doesn't exist so create it
            pass
        else:
            continue

        # remember our current working dir
        currentDir = os.getcwd()

        # get directory of target
        targetDirName = os.path.dirname(str(t))

        # change dirs to target directory
        os.chdir(targetDirName)

        # create symbolic link: symlink(source, linkname)
        symlink("../../include/"+linkname, linkname)

        # change back to original directory
        os.chdir(currentDir)

    return None


symLinkBuilder = Builder(action = buildSymbolicLinks)
env.Append(BUILDERS = {'CreateSymbolicLinks' : symLinkBuilder})

################################
# 64 or 32 bit operating system?
################################
    
# Define configure-type test function to
# see if operating system is 64 or 32 bits
def CheckHas64Bits(context, flags):
    context.Message( 'Checking for 64/32 bits ...' )
    lastCCFLAGS = context.env['CCFLAGS']
    lastLFLAGS  = context.env['LINKFLAGS']
    context.env.Append(CCFLAGS = flags, LINKFLAGS = flags)
    # (C) program to run to check for bits
    ret = context.TryRun("""
#include <stdio.h>
int main(int argc, char **argv) {
  printf(\"%d\", 8*sizeof(0L));
  return 0;
}
""", '.c')
    # restore original flags
    context.env.Replace(CCFLAGS = lastCCFLAGS, LINKFLAGS = lastLFLAGS)
    # if program successfully ran ...
    if ret[0]:
        context.Result(ret[1])
        if ret[1] == '64':
            return 64
        return 32
    # else if program did not run ...
    else:
        # Don't know if it's a 32 or 64 bit operating system
        context.Result('failed')
        return 0

# How many bits is the operating system?
# For Linux 64 bit x86 machines, the "machine' variable is x86_64,
# but for Darwin or Solaris there is no obvious check so run
# a configure-type test.
ccflags = ''
is64bits = False
if platform == 'Linux'and machine == 'x86_64':
        is64bits = True
        print 'Found 64 bit system'
else:
    if platform == 'SunOS':
        ccflags = '-xarch=amd64'
    # run the test
    conf = Configure( env, custom_tests = { 'CheckBits' : CheckHas64Bits } )
    ret = conf.CheckBits(ccflags)
    env = conf.Finish()
    if ret < 1:
        print 'Cannot run test, assume 32 bit system'
    elif ret == 64:
        print 'Found 64 bit system'
        is64bits = True;
    else:
        print 'Found 32 bit system'


#########################################
# Command line options (try scons -h)
#########################################

Help('\n-D                  build from subdirectory of package\n')

# debug option
AddOption('--dbg',
           dest='ddebug',
           default=False,
           action='store_true')
debug = GetOption('ddebug')
print "debug =", debug
Help('\nlocal scons OPTIONS:\n')
Help('--dbg               compile with debug flag\n')

# vxworks 5.5 option
AddOption('--vx5.5',
           dest='doVX55',
           default=False,
           action='store_true')
useVxworks55= GetOption('doVX55')
print "useVxworks 5.5 =", useVxworks55
Help('--vx5.5             cross compile for vxworks 5.5\n')

# vxworks 6.0 option
AddOption('--vx6.0',
           dest='doVX60',
           default=False,
           action='store_true')
useVxworks60= GetOption('doVX60')
print "useVxworks 6.0 =", useVxworks60
Help('--vx6.0             cross compile for vxworks 6.0\n')

# 32 bit option
AddOption('--32bits',
           dest='use32bits',
           default=False,
           action='store_true')
use32bits = GetOption('use32bits')
print "use32bits =", use32bits
Help('--32bits            compile 32bit libs & executables on 64bit system\n')

# Pthread read/write locks
AddOption('--no-rwlock',
           dest='norwlock',
           default=False,
           action='store_true')
noReadWriteLocks = GetOption('norwlock')
print "noReadWriteLocks =", noReadWriteLocks
Help('--no-rwlock         compile without pthread read/write locks\n')

# install directory option
AddOption('--prefix',
           dest='prefix',
           nargs=1,
           default='',
           action='store')
prefix = GetOption('prefix')
Help('--prefix=<dir>      use base directory <dir> when doing install\n')

AddOption('--incdir',
          dest='incdir',
          nargs=1,
          default=None,
          action='store')
incdir = GetOption('incdir')
Help('--incdir=<dir>      copy header  files to directory <dir> when doing install\n')

AddOption('--libdir',
          dest='libdir',
          nargs=1,
          default=None,
          action='store')
libdir = GetOption('libdir')
Help('--libdir=<dir>      copy library files to directory <dir> when doing install\n')

AddOption('--bindir',
          dest='bindir',
          nargs=1,
          default=None,
          action='store')
bindir = GetOption('bindir')
Help('--bindir=<dir>      copy binary  files to directory <dir> when doing install\n')

#########################
# Compile flags
#########################

# 2 possible versions of vxWorks
if useVxworks55:
    useVxworks = True
    vxVersion  = 5.5
    
elif useVxworks60:
    useVxworks = True
    vxVersion  = 6.0

else:
    useVxworks = False
    
# debug/optimization flags
debugSuffix = ''
if debug:
    debugSuffix = '-dbg'
    # compile with -g and add debugSuffix to all executable names
    env.Append(CCFLAGS = '-g', PROGSUFFIX = debugSuffix)

elif platform == 'SunOS':
    env.Append(CCFLAGS = '-xO3')

else:
    env.Append(CCFLAGS = '-O3')

vxInc = ''
execLibs = ['']

# If using vxworks
if useVxworks:
    osname = 'vxworks-ppc'

    ## Figure out which version of vxworks is being used.
    ## Do this by finding out which ccppc is first in our PATH.
    #vxCompilerPath = Popen('which ccppc', shell=True, stdout=PIPE, stderr=PIPE).communicate()[0]
    
    ## Then ty to grab the major version number from the PATH
    #matchResult = re.match('/site/vxworks/(\\d).+', vxCompilerPath)
    #if matchResult != None:
        ## Test if version number was obtained
        #try:
            #vxVersion = int(matchResult.group(1))
        #except IndexError:
            #print 'ERROR finding vxworks version, set to 6 by default\n'

    print '\nUsing vxWorks version ' + str(vxVersion) + '\n'

    if vxVersion == 5.5:
        vxbase = '/site/vxworks/5.5/ppc'
        vxInc  = [vxbase + '/target/h']
        env.Append(CPPDEFINES = ['VXWORKS_5'])
    elif vxVersion == 6.0:
        vxbase = '/site/vxworks/6.0/ppc/gnu/3.3.2-vxworks60'
        vxInc  = ['/site/vxworks/6.0/ppc/vxworks-6.0/target/h',
                  '/site/vxworks/6.0/ppc/vxworks-6.0/target/h/wrn/coreip']
        env.Append(CPPDEFINES = ['VXWORKS_6'])
    else:
        print 'Unknown version of vxWorks, exiting\n'
        raise SystemExit


    if platform == 'Linux':
        if vxVersion == 5:
            vxbin = vxbase + '/host/x86-linux/bin'
        else:
            vxbin = vxbase + '/x86-linux2/bin'
    elif platform == 'SunOS':
        if vxVersion > 5:
            print '\nVxworks 6.x compilation not allowed on solaris\n'
            raise SystemExit
        vxbin = vxbase + '/host/sun4-solaris2/bin'
        if machine == 'i86pc':
            print '\nVxworks compilation not allowed on x86 solaris\n'
            raise SystemExit
    else:
        print '\nVxworks compilation not allowed on ' + platform + '\n'
        raise SystemExit
                    
    env.Replace(SHLIBSUFFIX = '.o')
    # get rid of -shared and use -r
    env.Replace(SHLINKFLAGS = '-r')
    # redefine SHCFLAGS/SHCCFLAGS to get rid of -fPIC (in Linux)
    vxFlags = '-fno-builtin -fvolatile -fstrength-reduce -mlongcall -mcpu=604'
    env.Replace(SHCFLAGS  = vxFlags)
    env.Replace(SHCCFLAGS = vxFlags)
    env.Append(CFLAGS     = vxFlags)
    env.Append(CCFLAGS    = vxFlags)
    env.Append(CPPPATH    = vxInc)
    env.Append(CPPDEFINES = ['CPU=PPC604', 'VXWORKS', '_GNU_TOOL', 'VXWORKSPPC', 'POSIX_MISTAKE', 'NO_RW_LOCK'])
    env['CC']     = 'ccppc'
    env['CXX']    = 'g++ppc'
    env['SHLINK'] = 'ldppc'
    env['AR']     = 'arppc'
    env['RANLIB'] = 'ranlibppc'
    use32bits = True

# else if NOT using vxworks
else:
    if noReadWriteLocks:
        env.Append(CPPDEFINES = ['NO_RW_LOCK'])

    # platform dependent quantities
    execLibs = ['m', 'pthread', 'dl', 'rt']  # default to standard Linux libs
    if platform == 'SunOS':
        env.Append(CCFLAGS = '-mt')
        env.Append(CPPDEFINES = ['_GNU_SOURCE', '_REENTRANT', '_POSIX_PTHREAD_SEMANTICS', 'SunOS'])
        execLibs = ['m', 'posix4', 'pthread', 'socket', 'resolv', 'nsl', 'dl']
        if is64bits and not use32bits:
            if machine == 'sun4u':
                env.Append(CCFLAGS = '-xarch=native64 -xcode=pic32',
                           #LIBPATH = '/lib/64', # to do this we need to pass LIBPATH to lower level
                           LINKFLAGS = '-xarch=native64 -xcode=pic32')
            else:
                env.Append(CCFLAGS = '-xarch=amd64',
                           #LIBPATH = ['/lib/64', '/usr/ucblib/amd64'],
                           LINKFLAGS = '-xarch=amd64')
    
    elif platform == 'Darwin':
        execLibs = ['pthread', 'dl']
        env.Append(CPPDEFINES = 'Darwin', SHLINKFLAGS = '-multiply_defined suppress -flat_namespace -undefined suppress')
        env.Append(CCFLAGS = '-fmessage-length=0')
        if is64bits and not use32bits:
            env.Append(CCFLAGS = '-arch x86_64',
                       LINKFLAGS = '-arch x86_64 -Wl,-bind_at_load')
    
    elif platform == 'Linux':
        if is64bits and use32bits:
            env.Append(CCFLAGS = '-m32', LINKFLAGS = '-m32')
    
    if not is64bits and not use32bits:
        use32bits = True

if is64bits and use32bits:
    osname = osname + '-32'

print "OSNAME = ", osname

# hidden sub directory into which variant builds go
archDir = '.' + osname + debugSuffix

#########################
# Install stuff
#########################

codaHomeEnv = ''

# Are we going to install anything?
installingStuff = False
if 'install' in COMMAND_LINE_TARGETS or 'examples' in COMMAND_LINE_TARGETS :
    installingStuff = True


# It's possible no installation is being done
if not installingStuff:
    libInstallDir     = "dummy"
    incInstallDir     = "dummy"
    binInstallDir     = "dummy"
    archIncInstallDir = "dummy2"

else:
    # The installation directory is the user-specified "prefix"
    # by first choice, "CODA" secondly.
    # Any user specified command line installation path overrides default
    if (prefix == None) or (prefix == ''):
        # prefix not defined try CODA env var
        codaHomeEnv = os.getenv('CODA',"")
        if codaHomeEnv == "":
            if (incdir == None) or (libdir == None) or (bindir == None):
                print
                print "Need to define CODA, or use the --prefix option,"
                print "or all the --incdir, --libdir, and --bindir options."
                print
                raise SystemExit
        else:
            prefix = codaHomeEnv
            print "Default install directory = ", prefix
    else:
        print 'Cmdline install directory = ', prefix


    # set our install directories
    if incdir != None:
        incDir = incdir
        archIncDir = incdir
    else:
        archIncDir = prefix + "/" + osname + '/include'
        incDir = prefix + '/include'

    if libdir != None:
        libDir = libdir
    else:
        libDir = prefix + "/" + osname + '/lib'

    if bindir != None:
        binDir = bindir
    else:
        binDir = prefix + "/" + osname + '/bin'


    # func to determine absolute path
    def make_abs_path(d):
        if not d[0] in [sep,'#','/','.']:
            if d[1] != ':':
                d = '#' + d
        if d[:2] == '.'+sep:
            d = os.path.join(os.path.abspath('.'), d[2:])
        return d


    incInstallDir     = make_abs_path(incDir)
    archIncInstallDir = make_abs_path(archIncDir)
    libInstallDir     = make_abs_path(libDir)
    binInstallDir     = make_abs_path(binDir)

    # print our install directories
    print 'bin install dir  = ', binInstallDir
    print 'lib install dir  = ', libInstallDir
    print 'inc install dirs = ', incInstallDir, ", ", archIncInstallDir

# use "install" on command line to install libs & headers
Help('install             install libs, headers, and important binaries\n')

# uninstall option
Help('-c  install         uninstall libs, headers, and important binaries\n')

# use "examples" on command line to install executable examples
Help('examples            install example programs\n')

# uninstall option
Help('-c  examples        uninstall example programs\n')

# not necessary to create install directories explicitly
# (done automatically during install)

##################################################
# Special Include Directory for java header files
##################################################

# Because we're using JNI, we need access to <jni.h> when compiling.
# If JAVA_HOME is defined, use that
javaHome = os.getenv('JAVA_HOME',"")
javaHomeInc = javaHome + "/include"

if javaHome != "":
    print 'Looking for jni.h in JAVA_HOME :'
    print '        ', javaHomeInc
    print '        ', javaHomeInc + "/linux"
    env.AppendUnique(CPPPATH = [javaHomeInc, javaHomeInc + "/linux"])

else:
    # If we are using a java installed in a non-standard place, then
    # this is located in <jdk>/include and possibly <jdk>/include/linux
    # which we'll want in our includes.
    # Do this by finding out where java (<jdk>/bin) is.
    javaPath = Popen('which java', shell=True, stdout=PIPE, stderr=PIPE).communicate()[0]

    # strip whitespace on end, then "java" on end
    javaIncPath = str(javaPath).rstrip().rstrip('java') + "../include"

    print 'JAVA_HOME is not defined so trying to find jni.h in :'
    print '          ', javaIncPath
    print '          ', javaIncPath + "/linux"
    env.AppendUnique(CPPPATH = [javaIncPath, javaIncPath + "/linux" ])

###########################
# Documentation generation
###########################

if 'doc' in COMMAND_LINE_TARGETS:
    # Functions that do the documentation creation
    def docGeneratorJava(target, source, env):
        cmd = 'ant javadoc'
        output = os.popen(cmd).read()
        cmd2 = 'rm -f undoc'
        output = os.popen(cmd2).read()
        return

    # doc files builders
    docBuildJava = Builder(action = docGeneratorJava)
    env.Append(BUILDERS = {'DocGenJava' : docBuildJava})

    # generate Java documentation
    env.Alias('doc', env.DocGenJava(target = ['#/doc/javadoc/index.html'],
            source = scanFiles("java/org/jlab/coda/cMsg", accept=["*.java"]) ))


# use "doc" on command line to create tar file
Help('doc                 create javadoc (in ./doc)\n')

# undoc file is just a device so we can use "undoc" as target of scons
if 'undoc' in COMMAND_LINE_TARGETS:
    def docRemover(target, source, env):
        cmd = 'touch undoc'
        output = os.popen(cmd).read()
        cmd1 = 'rm -fr doc/javadoc'
        output = os.popen(cmd1).read()
        return

    docRemoveAll = Builder(action = docRemover)
    env.Append(BUILDERS = {'DocRemove' : docRemoveAll})

    # remove documentation
    env.Alias('undoc', env.DocRemove(target = ['#/undoc'], source = None))


# use "undoc" on command line to create tar file
Help('undoc               remove javadoc (in ./doc)\n')

#########################
# Tar file
#########################

# Function that does the tar. Note that tar on Solaris is different
# (more primitive) than tar on Linux and MacOS. Solaris tar has no -z option
# and the exclude file does not allow wildcards. Thus, stick to Linux for
# creating the tar file.
def tarballer(target, source, env):
    if platform == 'SunOS':
        print '\nMake tar file from Linux or MacOS please\n'
        return
    dirname = os.path.basename(os.path.abspath('.'))
    cmd = 'tar -X tar/tarexclude -C .. -c -z -f ' + str(target[0]) + ' ./' + dirname
    pipe = Popen(cmd, shell=True, stdin=PIPE).stdout
    return pipe

# name of tarfile (software package dependent)
tarfile = 'tar/et-' + versionMajor + '.' + versionMinor + '.tgz'

# tarfile builder
tarBuild = Builder(action = tarballer)
env.Append(BUILDERS = {'Tarball' : tarBuild})
env.Alias('tar', env.Tarball(target = tarfile, source = None))

# use "tar" on command line to create tar file
Help('tar                 create tar file (in ./tar)\n')

######################################################
# Lower level scons files (software package dependent)
######################################################

# make available to lower level scons files
Export('env archDir incInstallDir libInstallDir binInstallDir archIncInstallDir execLibs debugSuffix')

# run lower level build files

# by default only make libs and et_start (in execsrc)
if useVxworks:
    env.SConscript('src/libsrc/SConscript.vx',   variant_dir='src/libsrc/'+archDir,   duplicate=0)
else:
    env.SConscript('src/libsrc/SConscript',  variant_dir='src/libsrc/'+archDir,  duplicate=0)
    env.SConscript('src/execsrc/SConscript', variant_dir='src/execsrc/'+archDir, duplicate=0)

if 'examples' in COMMAND_LINE_TARGETS:
    # for vxworks
    if useVxworks:
        env.SConscript('src/examples/SConscript.vx', variant_dir='src/examples/'+archDir, duplicate=0)
    else:
        env.SConscript('src/examples/SConscript', variant_dir='src/examples/'+archDir, duplicate=0)

