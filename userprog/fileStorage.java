package nachos.userprog;
import nachos.machine.*;
public class fileStorage 
{
	fileStorage(OpenFile newFile)
	{
		this.m_open_file = newFile;
		m_file_name = newFile.getName();
		num_of_usage = 1;
	}
	public int getNumOfUsage()
	{
		return num_of_usage;
	}
	public boolean unlinked()
	{
		return m_unlinked;
	}
	public OpenFile getOpenFile()
	{
		return m_open_file;
	}
	public String getFileName()
	{
		return m_file_name;
	}
	void reference()
	{
		++num_of_usage;
	}
	void dereference()
	{
		--num_of_usage;
	}
	void setUnlinked()
	{
		m_unlinked = true;
	}
	private OpenFile m_open_file;
	private int num_of_usage;
	private String m_file_name;
	private boolean m_unlinked = false;
}
