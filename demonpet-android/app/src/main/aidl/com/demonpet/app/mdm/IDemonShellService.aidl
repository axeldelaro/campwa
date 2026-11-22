package com.demonpet.app.mdm;

interface IDemonShellService {
    void destroy() = 16777114;
    String runCommand(String cmd) = 1;
}
