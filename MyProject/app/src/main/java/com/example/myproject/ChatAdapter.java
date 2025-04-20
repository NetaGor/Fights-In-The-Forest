/**
 * ChatAdapter.java - Adapter for displaying chat messages
 *
 * This adapter handles the display of chat messages in a RecyclerView,
 * binding message text to the chat_item layout.
 */
package com.example.myproject;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {
    private List<String> chatMessages; // List of chat messages to display

    /**
     * Constructor for the adapter
     *
     * @param chatMessages List of message strings to display
     */
    public ChatAdapter(List<String> chatMessages) {
        this.chatMessages = chatMessages;
    }

    /**
     * Creates a new view holder when needed
     *
     * @param parent The ViewGroup into which the new View will be added
     * @param viewType The view type of the new View
     * @return A new ViewHolder that holds a View of the given view type
     */
    @Override
    public ChatViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.chat_item, parent, false);
        return new ChatViewHolder(view);
    }

    /**
     * Binds the data at the specified position to the view holder
     *
     * @param holder The ViewHolder to bind data to
     * @param position The position of the data in the dataset
     */
    @Override
    public void onBindViewHolder(ChatViewHolder holder, int position) {
        holder.messageText.setText(chatMessages.get(position));
    }

    /**
     * Returns the total number of items in the dataset
     *
     * @return The total number of items
     */
    @Override
    public int getItemCount() {
        return chatMessages.size();
    }

    /**
     * ViewHolder class for chat messages
     */
    public static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView messageText; // TextView for displaying the message

        /**
         * Constructor for the ViewHolder
         *
         * @param itemView The view for this holder
         */
        public ChatViewHolder(View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.chat_message);
        }
    }
}
