package jdos.win.builtin.user32;

import jdos.hardware.Memory;
import jdos.win.Win;
import jdos.win.builtin.WinAPI;
import jdos.win.builtin.gdi32.WinDC;
import jdos.win.system.*;
import jdos.win.utils.StringUtil;

import java.util.Iterator;
import java.util.LinkedList;

public class WinWindow extends WinObject {
    static public WinWindow create() {
        return new WinWindow(nextObjectId());
    }

    static public WinWindow get(int handle) {
        WinObject object = getObject(handle);
        if (object == null || !(object instanceof WinWindow))
            return null;
        return (WinWindow)object;
    }

    // HWND WINAPI CreateWindowEx(DWORD dwExStyle, LPCTSTR lpClassName, LPCTSTR lpWindowName, DWORD dwStyle, int x, int y, int nWidth, int nHeight, HWND hWndParent, HMENU hMenu, HINSTANCE hInstance, LPVOID lpParam)
    static public int CreateWindowExA(int dwExStyle, int lpClassName, int lpWindowName, int dwStyle, int x, int y, int nWidth, int nHeight, int hWndParent, int hMenu, int hInstance, int lpParam) {
        if ((dwExStyle & WS_EX_MDICHILD)!=0) {
            Win.panic("MDI not supported yet");
        }
        int hwndOwner = 0;

        /* Find the parent window */
        if (hWndParent == HWND_MESSAGE) {

        } else if (hWndParent!=0) {
            if ((dwStyle & (WS_CHILD|WS_POPUP)) != WS_CHILD) {
                hwndOwner = hWndParent;
                hWndParent = 0;
            }
        } else {
            if ((dwStyle & (WS_CHILD|WS_POPUP)) == WS_CHILD) {
                warn("No parent for child window\n" );
                SetLastError(ERROR_TLW_WITH_WSCHILD);
                return 0;  /* WS_CHILD needs a parent, but WS_POPUP doesn't */
            }
        }

        if ((dwExStyle & WS_EX_DLGMODALFRAME)!=0 ||
            (((dwExStyle & WS_EX_STATICEDGE)==0) &&
              (dwStyle & (WS_DLGFRAME | WS_THICKFRAME))!=0))
            dwExStyle |= WS_EX_WINDOWEDGE;
        else
            dwExStyle &= ~WS_EX_WINDOWEDGE;

        WinClass winClass;
        if (lpClassName<=0xFFFF) {
            winClass = WinClass.get(lpClassName);
        } else {
            String className = StringUtil.getString(lpClassName);
            winClass = (WinClass)WinSystem.getCurrentProcess().classNames.get(className);
        }
        if (winClass == null) {
            SetLastError(ERROR_CANNOT_FIND_WND_CLASS);
            return 0;
        }
        WinWindow wndPtr = WinWindow.create();
        int hwnd = wndPtr.getHandle();

        /* Fill the window structure */
        wndPtr.thread = Scheduler.getCurrentThread();
        wndPtr.hInstance = hInstance;
        wndPtr.winClass = winClass;
        wndPtr.winproc = winClass.eip;
        wndPtr.text           = null;
        wndPtr.dwStyle        = dwStyle & ~WS_VISIBLE;
        wndPtr.dwExStyle      = dwExStyle;
        wndPtr.wIDmenu        = 0;
        wndPtr.helpContext    = 0;
        //wndPtr->pScroll        = NULL;
        wndPtr.userdata       = 0;
        wndPtr.hIcon          = 0;
        wndPtr.hIconSmall     = 0;
        wndPtr.hSysMenu       = 0;
        wndPtr.parent         = hWndParent;
        wndPtr.owner          = hwndOwner;

        //if ((dwStyle & WS_SYSMENU)!=0) SetSystemMenu(hwnd, 0);

        /*
         * Correct the window styles.
         *
         * It affects only the style loaded into the WIN structure.
         */

        if ((wndPtr.dwStyle & (WS_CHILD | WS_POPUP)) != WS_CHILD)
        {
            wndPtr.dwStyle |= WS_CLIPSIBLINGS;
            if ((wndPtr.dwStyle & WS_POPUP)==0)
                wndPtr.dwStyle |= WS_CAPTION;
        }

        /*
         * WS_EX_WINDOWEDGE appears to be enforced based on the other styles, so
         * why does the user get to set it?
         */

        if ((wndPtr.dwExStyle & WS_EX_DLGMODALFRAME)!=0 ||
              (wndPtr.dwStyle & (WS_DLGFRAME | WS_THICKFRAME))!=0)
            wndPtr.dwExStyle |= WS_EX_WINDOWEDGE;
        else
            wndPtr.dwExStyle &= ~WS_EX_WINDOWEDGE;

        if ((wndPtr.dwStyle & (WS_CHILD | WS_POPUP))==0) {
            wndPtr.flags |= WIN_NEED_SIZE;
        }

        /* Set the window menu */
        if ((wndPtr.dwStyle & (WS_CHILD | WS_POPUP)) != WS_CHILD) {
            if (hMenu!=0) {
                if (WinMenu.SetMenu(hwnd, hMenu)==0) {
                    wndPtr.close();
                    return 0;
                }
            } else if (winClass.pMenuName!=0) {
                hMenu = WinMenu.LoadMenuA(hInstance, winClass.pMenuName);
                if (hMenu!=0) WinMenu.SetMenu(hwnd, hMenu);
            }
        }
        wndPtr.wIDmenu = hMenu;

        /* call the WH_CBT hook */
        // CBT_CREATEWND
            // LPCREATESTRUCT lpcs;
            // HWND  hwndInsertAfter;

        int cbcs = getTempBuffer(CREATESTRUCT.SIZE);
        int cbtc = getTempBuffer(8);
        CREATESTRUCT.write(cbcs, lpParam, hInstance, hMenu, wndPtr.parent, nWidth, nHeight, y, x, dwStyle, lpWindowName, lpClassName, dwExStyle);
        writed(cbtc, cbcs);
        writed(cbtc+4, HWND_TOP);

        if (Hook.HOOK_CallHooks( WH_CBT, HCBT_CREATEWND, hwnd, cbtc)!=0) {
            wndPtr.close();
            return 0;
        }

        /* send the WM_GETMINMAXINFO message and fix the size if needed */
        CREATESTRUCT cs = new CREATESTRUCT(cbcs);

        int cx = cs.cx;
        int cy = cs.cy;
        if ((dwStyle & WS_THICKFRAME)!=0 || (dwStyle & (WS_POPUP | WS_CHILD))==0) {
            // :TODO: min/max stuff
        }

        wndPtr.rectWindow.set(0, 0, SysParams.GetSystemMetrics(SM_CXSCREEN), SysParams.GetSystemMetrics(SM_CYSCREEN));
        wndPtr.rectClient=wndPtr.rectWindow.copy();

        /* send WM_NCCREATE */
        if (Message.SendMessageA(wndPtr.handle, WM_NCCREATE, 0, cbcs)==0) {
            warn(hwnd+": aborted by WM_NCCREATE\n");
            wndPtr.close();
            return 0;
        }

        /* send WM_NCCALCSIZE */
        WinRect rect = new WinRect();
        if (WIN_GetRectangles( hwnd, COORDS_PARENT, rect, null)) {
            /* yes, even if the CBT hook was called with HWND_TOP */
            int insert_after = (GetWindowLongA(hwnd, GWL_STYLE ) & WS_CHILD)!=0 ? HWND_BOTTOM : HWND_TOP;
            WinRect client_rect = rect.copy();

            WinWindow parent = WinWindow.get(wndPtr.parent);
            /* the rectangle is in screen coords for WM_NCCALCSIZE when wparam is FALSE */
            if (parent != null)
                parent.windowToScreen(client_rect);
            int pRect = client_rect.allocTemp();
            Message.SendMessageA(wndPtr.handle, WM_NCCALCSIZE, FALSE, pRect);
            client_rect = new WinRect(pRect);
            if (parent != null)
                parent.screenToWindow(client_rect);
            WinPos.SetWindowPos(hwnd, insert_after, client_rect.left, client_rect.top, client_rect.width(), client_rect.height(), SWP_NOACTIVATE);
        }
        else {
            wndPtr.close();
            return 0;
        }

        /* send WM_CREATE */
        if (Message.SendMessageA(wndPtr.handle, WM_CREATE, 0, cbcs) == -1) {
            wndPtr.close();
            return 0;
        }

        /* send the size messages */
        Message.SendMessageA(wndPtr.handle, WM_SIZE, SIZE_RESTORED, MAKELONG(wndPtr.rectWindow.width(), wndPtr.rectWindow.height()));
        Message.SendMessageA(wndPtr.handle, WM_MOVE, 0, MAKELONG(wndPtr.rectWindow.left, wndPtr.rectWindow.top));

        Scheduler.getCurrentThread().windows.add(wndPtr);

        /* Notify the parent window only */
        wndPtr.parentNotify(WM_CREATE);
        if (IsWindow(hwnd)==FALSE)
            return 0;

        if ((dwStyle & WS_VISIBLE)!=0) {
            WinPos.ShowWindow(hwnd, SW_SHOW);
        }

        /* Call WH_SHELL hook */
        if ((wndPtr.dwStyle & WS_CHILD)==0 && wndPtr.owner == 0)
            Hook.HOOK_CallHooks(WH_SHELL, HSHELL_WINDOWCREATED, hwnd, 0);

        return hwnd;
    }

    // BOOL WINAPI DestroyWindow(HWND hWnd)
    public static int DestroyWindow(int hWnd) {
        Win.panic("DestroyWindow not implemented yet");
        return TRUE;
    }

    // HWND WINAPI FindWindow(LPCTSTR lpClassName, LPCTSTR lpWindowName)
    public static int FindWindowA(int lpClassName, int lpWindowName) {
        return FindWindowExA(0, 0, lpClassName, lpWindowName);
    }

    // HWND WINAPI FindWindowEx(HWND hwndParent, HWND hwndChildAfter, LPCTSTR lpszClass, LPCTSTR lpszWindow)
    public static int FindWindowExA(int hwndParent, int hwndChildAfter, int lpszClass, int lpszWindow) {
        WinClass winClass = null;
        if (lpszClass != 0) {
            if (lpszClass <= 0xFFFF)
                winClass = WinClass.get(lpszClass);
            else
                winClass = (WinClass)getNamedObject(StringUtil.getString(lpszClass));
        }
        String name = null;
        if (lpszWindow != 0)
            name = StringUtil.getString(lpszWindow);
        if (hwndParent == 0)
            hwndParent = StaticData.desktopWindow;
        if (hwndParent == HWND_MESSAGE)
            Win.panic("FindWindowExe HWND_MESSAGE not supported yet");
        WinWindow parent = WinWindow.get(hwndParent);
        if (parent == null)
            return 0;
        Iterator<WinWindow> children = parent.getChildren();
        int buffer = 0;
        int bufferLen = 0;
        if (name != null) {
            bufferLen = name.length()+2;
            buffer = getTempBuffer(bufferLen);
        }
        while (children.hasNext()) {
            WinWindow child = children.next();
            if (hwndChildAfter != 0) {
                if (child.handle == hwndChildAfter)
                    hwndChildAfter = 0;
            } else {
                if (winClass != null && child.winClass != winClass)
                    continue;
                if (name != null) {
                    int count = GetWindowTextA(child.handle, buffer, bufferLen);
                    if (count <0 || StringUtil.strncmp(buffer, name, bufferLen)!=0)
                        continue;
                }
            }
            return child.handle;
        }
        return 0;
    }

    // HWND WINAPI GetAncestor( HWND hwnd, UINT type )
    public static int GetAncestor(int hwnd, int type) {
        WinWindow win;

        if ((win = WinWindow.get(hwnd))==null) {
            SetLastError(ERROR_INVALID_WINDOW_HANDLE);
            return 0;
        }
        switch(type)
        {
        case GA_PARENT:
            return win.parent;
        case GA_ROOT:
            while (win.parent != 0) {
                win = WinWindow.get(win.parent);
            }
            return win.handle;
        case GA_ROOTOWNER:
            while (true) {
                int parent = GetParent(hwnd);
                if (parent != 0)
                    hwnd = parent;
                else
                    break;
            }
            return hwnd;
        }
        return 0;
    }


    // HWND WINAPI GetDesktopWindow(void)
    static public int GetDesktopWindow() {
        return StaticData.desktopWindow;
    }

    // HWND WINAPI GetParent( HWND hwnd )
    static public int GetParent(int hwnd) {
        WinWindow wndPtr;
        int retvalue = 0;

        if ((wndPtr = WinWindow.get(hwnd))==null) {
            SetLastError( ERROR_INVALID_WINDOW_HANDLE );
            return 0;
        }

        if ((wndPtr.dwStyle & WS_POPUP)!=0) retvalue = wndPtr.owner;
        else if ((wndPtr.dwStyle & WS_CHILD)!=0) retvalue = wndPtr.parent;
        return retvalue;
    }

    // HWND WINAPI GetWindow( HWND hwnd, UINT rel )
    static public int GetWindow(int hwnd, int rel) {
        int retval = 0;

        WinWindow wndPtr = WinWindow.get(hwnd);
        if (wndPtr==null)
        {
            SetLastError( ERROR_INVALID_HANDLE );
            return 0;
        }
        switch(rel)
        {
        case GW_HWNDFIRST:
        {
            WinWindow parent = WinWindow.get(wndPtr.parent);
            if (parent == null)
                return 0;
            return parent.children.getFirst().handle;
        }
        case GW_HWNDLAST:
        {
            WinWindow parent = WinWindow.get(wndPtr.parent);
            if (parent == null)
                return 0;
            return parent.children.getLast().handle;
        }
        case GW_HWNDNEXT:
        {
            WinWindow parent = WinWindow.get(wndPtr.parent);
            if (parent == null)
                return 0;
            int index = parent.children.indexOf(wndPtr);
            if (index+1>=parent.children.size())
                return 0;
            return wndPtr.children.get(index+1).handle;
        }
        case GW_HWNDPREV:
        {
            WinWindow parent = WinWindow.get(wndPtr.parent);
            if (parent == null)
                return 0;
            int index = parent.children.indexOf(wndPtr);
            if (index==0)
                return 0;
            return wndPtr.children.get(index-1).handle;
        }
        case GW_OWNER:
            return wndPtr.owner;
        case GW_CHILD:
            if (wndPtr.children.size()==0)
                return 0;
            return wndPtr.children.getFirst().handle;
        }
        return 0;
    }

    // LONG WINAPI GetWindowLongA( HWND hwnd, INT offset );
    public static int GetWindowLongA(int hwnd, int offset) {
        return WIN_GetWindowLong( hwnd, offset, 4, FALSE );
    }

    // int WINAPI GetWindowText(HWND hWnd, LPTSTR lpString, int nMaxCount)
    public static int GetWindowTextA(int hWnd, int lpString, int nMaxCount) {
        return Message.SendMessageA(hWnd, WM_GETTEXT, nMaxCount, lpString);
    }

    // DWORD WINAPI GetWindowThreadProcessId( HWND hwnd, LPDWORD process )
    static public int GetWindowThreadProcessId(int hwnd, int process) {
        WinWindow ptr = WinWindow.get(hwnd);

        if (ptr == null) {
            SetLastError( ERROR_INVALID_WINDOW_HANDLE);
            return 0;
        }
        if (process != 0)
            writed(process, ptr.thread.getProcess().handle);
        return ptr.thread.handle;
    }

    // BOOL WINAPI IsChild(HWND hWndParent, HWND hWnd)
    static public int IsChild(int hWndParent, int hWnd) {
        WinWindow parent = WinWindow.get(hWndParent);
        WinWindow child = WinWindow.get(hWnd);
        if (parent == null)
            return FALSE;
        return BOOL(parent.children.contains(child));
    }

    // BOOL WINAPI IsWindow( HWND hwnd )
    public static int IsWindow(int hwnd) {
        return BOOL(WinWindow.get(hwnd)!=null);
    }

    // BOOL WINAPI IsWindowVisible(HWND hWnd)
    public static int IsWindowVisible(int hWnd) {
        WinWindow window = WinWindow.get(hWnd);

        while (window != null && (window.dwStyle & WS_VISIBLE) != 0) {
            if (window.parent == 0)
                return TRUE;
            window = window.parent();
        }
        return FALSE;
    }

    static private int WIN_GetWindowLong(int hwnd, int offset, int size, int unicode) {
        WinWindow wndPtr;

        if (offset == GWLP_HWNDPARENT) {
            int parent = GetAncestor( hwnd, GA_PARENT );
            if (parent == 0) parent = GetWindow( hwnd, GW_OWNER );
            return parent;
        }

        if ((wndPtr = WinWindow.get(hwnd))==null) {
            SetLastError( ERROR_INVALID_WINDOW_HANDLE );
            return 0;
        }

        if (offset == GWLP_WNDPROC && (!wndPtr.isInCurrentProcess() || wndPtr.isDesktop())) {
            SetLastError( ERROR_ACCESS_DENIED );
            return 0;
        }

        if (offset >= 0) {
            if (offset > wndPtr.cbWndExtra - size) {
                warn("Invalid offset "+offset);
                SetLastError( ERROR_INVALID_INDEX );
                return 0;
            }
            return wndPtr.readExtraData(offset, size);
        }

        switch(offset)
        {
            case GWLP_USERDATA:  return wndPtr.userdata;
            case GWL_STYLE:      return wndPtr.dwStyle;
            case GWL_EXSTYLE:    return wndPtr.dwExStyle;
            case GWLP_ID:        return wndPtr.wIDmenu;
            case GWLP_HINSTANCE: return wndPtr.hInstance;
            case GWLP_WNDPROC:   return wndPtr.winproc;
            default:
                warn("Unknown offset "+offset);
                SetLastError( ERROR_INVALID_INDEX );
                break;
        }
        return 0;
    }

    public static boolean WIN_GetRectangles(int hwnd, int relative, WinRect rectWindow, WinRect rectClient ) {
        WinWindow win = WinWindow.get(hwnd);
        boolean ret = true;

        if (win == null) {
            SetLastError(ERROR_INVALID_WINDOW_HANDLE);
            return false;
        }
        if (win.isDesktop())
        {
            WinRect rect = new WinRect();
            rect.left = rect.top = 0;
            rect.right  = SysParams.GetSystemMetrics(SM_CXSCREEN);
            rect.bottom = SysParams.GetSystemMetrics(SM_CYSCREEN);
            if (rectWindow!=null) rectWindow.copy(rect);
            if (rectClient!=null) rectClient.copy(rect);
            return true;
        }

        WinRect window_rect = win.rectWindow.copy();
        WinRect client_rect = win.rectClient.copy();

        switch (relative)
        {
        case COORDS_CLIENT:
            window_rect.offset(-win.rectClient.left, -win.rectClient.top);
            client_rect.offset(-win.rectClient.left, -win.rectClient.top);
            break;
        case COORDS_WINDOW:
            window_rect.offset(-win.rectWindow.left, -win.rectWindow.top);
            client_rect.offset(-win.rectWindow.left, -win.rectWindow.top);
            break;
        case COORDS_PARENT:
            break;
        case COORDS_SCREEN:
            while (win.parent!=0) {
                win = win.parent();
                if (win.isDesktop()) break;
                if (win.parent!=0) {
                    window_rect.offset(win.rectClient.left, win.rectClient.top);
                    client_rect.offset(win.rectClient.left, win.rectClient.top );
                }
            }
            break;
        }
        if (rectWindow != null) rectWindow.copy(window_rect);
        if (rectClient!=null) rectClient.copy(client_rect);
        return true;
    }

    private WinWindow(int id) {
        super(id);
    }

    public WinTimer timer = new WinTimer(handle);

    public WinRect rectWindow = new WinRect();
    public WinRect rectClient = new WinRect();
    public int dwStyle;
    private int dwExStyle;
    private int cbWndExtra;
    private int id = 0;
    private int hInstance;
    int wIDmenu;
    public int parent;
    public int owner;
    private WinThread thread;
    protected int winproc;
    private int wExtra;
    private int userdata;
    public String text="";
    private int helpContext;
    private int hIcon;
    private int hIconSmall;
    private int hSysMenu;
    public int flags;
    public WinPoint min_pos = new WinPoint();
    public WinPoint max_pos = new WinPoint();
    public WinRect normal_rect = new WinRect();

    private WinDC dc;
    WinClass winClass;

    public boolean isActive = false;
    private boolean needsPainting = false;

    public LinkedList<WinWindow> children = new LinkedList<WinWindow>(); // first one is on top

    // Used by desktop
    public WinWindow(int id, WinClass winClass, String name) {
        super(id);
        this.winClass = winClass;
        this.name = name;
        this.rectWindow = new WinRect(0, 0, WinSystem.getScreenWidth(), WinSystem.getScreenHeight());
        this.rectClient = new WinRect(0, 0, this.rectWindow.width(), this.rectWindow.height());
    }

    public boolean isInCurrentProcess() {
        return thread.getProcess() == WinSystem.getCurrentProcess();
    }

    public boolean isDesktop() {
        return handle == StaticData.desktopWindow;
    }

    public WinWindow parent() {
        return WinWindow.get(parent);
    }

    public void invalidate() {
        if (needsPainting == false) {
            needsPainting = true;
            if (thread != null)
                thread.paintReady();
        }
    }

    public void validate() {
        needsPainting = false;
    }

    public boolean needsPainting() {
        return needsPainting;
    }

    public int readExtraData(int offset, int size) {
        if (size == 4)
            return Memory.mem_readd(wExtra+offset);
        if (size == 2)
            return Memory.mem_readw(wExtra + offset);
        return 0;
    }

    public WinWindow findWindowFromPoint(int x, int y) {
        Iterator<WinWindow> i = children.iterator();
        while (i.hasNext()) {
            WinWindow child = i.next();
            if ((child.dwStyle & WS_VISIBLE)!=0 &&  child.rectWindow.contains(x, y)) {
                return child.findWindowFromPoint(x-child.rectWindow.left, y-child.rectWindow.top);
            }
        }
        return this;
    }

    public int findWindow(String className, String windowName) {
        if (this.winClass.className.equals(className) || this.name.equals(windowName))
            return getHandle();
        Iterator<WinWindow> i = children.iterator();
        while (i.hasNext()) {
            WinWindow child = i.next();
            int result = child.findWindow(className, windowName);
            if (result != 0)
                return result;
        }
        return 0;
    }

    public int invalidateRect(int lpRect, int bErase) {
        needsPainting = true;
        return WinAPI.TRUE;
    }

    public void postMessage(int msg, int wParam, int lParam) {
        thread.postMessage(handle, msg, wParam, lParam);
    }

    public WinThread getThread() {
        return thread;
    }

    public WinWindow getParent() {
        if ((dwStyle & WS_POPUP)!=0) return get(owner);
        else if ((dwStyle & WS_CHILD)!=0) return get(parent);
        return null;
    }

    public Iterator<WinWindow> getChildren() {
        return children.iterator();
    }

    public WinDC getDC() {
        WinDC dc;

        dc = this.dc;
        if (dc == null) {
            dc = winClass.dc;
        }

        if (dc==null) {
            int class_style = winClass.style;

            if ((class_style & CS_CLASSDC)!=0) {
                dc = WinDC.create();
                winClass.dc = dc;
                dc.makePermanent();
            } else if ((class_style & CS_OWNDC)!=0) {
                dc = WinDC.create();
                this.dc = dc;
                dc.makePermanent();
            }
        }
        if (dc == null)
            dc = WinDC.create(StaticData.screen, false);
        return dc;
    }

    public WinPoint getScreenOffset() {
        WinPoint offset = new WinPoint();
        WinWindow window = this;

        while (window != null) {
            offset.x += window.rectClient.left;
            offset.y += window.rectClient.top;
            window = window.parent();
        }
        return offset;
    }

    public void windowToScreen(WinRect rect) {
        WinPoint p = getScreenOffset();
        rect.offset(p.x, p.y);
    }

    public void screenToWindow(WinRect rect) {
        WinPoint p = getScreenOffset();
        rect.offset(-p.x, -p.y);
    }

    public void windowToScreen(WinPoint pt) {
        WinPoint p = getScreenOffset();
        pt.offset(p.x, p.y);
    }

    public void screenToWindow(WinPoint pt) {
        WinPoint p = getScreenOffset();
        pt.offset(-p.x, -p.y);
    }

    public void parentNotify(int msg) {
        if ((dwStyle & (WS_CHILD | WS_POPUP)) == WS_CHILD && (dwExStyle & WS_EX_NOPARENTNOTIFY)==0) {
            Message.SendMessageA(GetParent(handle), WM_PARENTNOTIFY, MAKEWPARAM(msg, wIDmenu), handle);
        }
    }
}
