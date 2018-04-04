package nachos.userprog;
import nachos.machine.*;
public class childProcess 
{
	public UserProcess m_process;
	public Integer m_exit_val;
	public childProcess(UserProcess in_process)
	{
		m_exit_val = null;
		m_process = in_process;
	}
}
